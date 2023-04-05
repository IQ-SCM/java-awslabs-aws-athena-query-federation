/*-
 * #%L
 * Amazon Athena Query Federation SDK
 * %%
 * Copyright (C) 2019 - 2023 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connector.lambda.handlers;

import com.amazonaws.athena.connector.lambda.QueryStatusChecker;
import com.amazonaws.athena.connector.lambda.ThrottlingInvoker;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockSpiller;
import com.amazonaws.athena.connector.lambda.data.S3BlockSpiller;
import com.amazonaws.athena.connector.lambda.data.SpillConfig;
import com.amazonaws.athena.connector.lambda.domain.predicate.ConstraintEvaluator;
import com.amazonaws.athena.connector.lambda.proto.records.ReadRecordsRequest;
import com.amazonaws.athena.connector.lambda.proto.records.ReadRecordsResponse;
import com.amazonaws.athena.connector.lambda.proto.records.RemoteReadRecordsResponse;
import com.amazonaws.athena.connector.lambda.security.CachableSecretsManager;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufMessageConverter;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufSerDe;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.athena.AmazonAthenaClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.stream.Collectors;

import static com.amazonaws.athena.connector.lambda.handlers.AthenaExceptionFilter.ATHENA_EXCEPTION_FILTER;

/**
 * More specifically, this class is responsible for providing Athena with actual rows level data from our simulated
 * source. Athena will call readWithConstraint(...) on this class for each 'Split' we generated in MetadataHandler.
 */
public abstract class RecordHandler
{
    private static final Logger logger = LoggerFactory.getLogger(RecordHandler.class);
    private static final String MAX_BLOCK_SIZE_BYTES = "MAX_BLOCK_SIZE_BYTES";
    private static final int NUM_SPILL_THREADS = 2;
    protected final java.util.Map<String, String> configOptions;
    private final AmazonS3 amazonS3;
    private final String sourceType;
    private final CachableSecretsManager secretsManager;
    private final AmazonAthena athena;
    private final ThrottlingInvoker athenaInvoker;

    /**
     * @param sourceType Used to aid in logging diagnostic info when raising a support case.
     */
    public RecordHandler(String sourceType, java.util.Map<String, String> configOptions)
    {
        this.sourceType = sourceType;
        this.amazonS3 = AmazonS3ClientBuilder.defaultClient();
        this.secretsManager = new CachableSecretsManager(AWSSecretsManagerClientBuilder.defaultClient());
        this.athena = AmazonAthenaClientBuilder.defaultClient();
        this.configOptions = configOptions;
        this.athenaInvoker = ThrottlingInvoker.newDefaultBuilder(ATHENA_EXCEPTION_FILTER, configOptions).build();
    }

    /**
     * @param sourceType Used to aid in logging diagnostic info when raising a support case.
     */
    public RecordHandler(AmazonS3 amazonS3, AWSSecretsManager secretsManager, AmazonAthena athena, String sourceType, java.util.Map<String, String> configOptions)
    {
        this.sourceType = sourceType;
        this.amazonS3 = amazonS3;
        this.secretsManager = new CachableSecretsManager(secretsManager);
        this.athena = athena;
        this.configOptions = configOptions;
        this.athenaInvoker = ThrottlingInvoker.newDefaultBuilder(ATHENA_EXCEPTION_FILTER, configOptions).build();
    }

    /**
     * Resolves any secrets found in the supplied string, for example: MyString${WithSecret} would have ${WithSecret}
     * by the corresponding value of the secret in AWS Secrets Manager with that name. If no such secret is found
     * the function throws.
     *
     * @param rawString The string in which you'd like to replace SecretsManager placeholders.
     * (e.g. ThisIsA${Secret}Here - The ${Secret} would be replaced with the contents of an SecretsManager
     * secret called Secret. If no such secret is found, the function throws. If no ${} are found in
     * the input string, nothing is replaced and the original string is returned.
     */
    protected String resolveSecrets(String rawString)
    {
        return secretsManager.resolveSecrets(rawString);
    }

    protected String getSecret(String secretName)
    {
        return secretsManager.getSecret(secretName);
    }

    // protobuf handler
    protected final void doHandleRequest(BlockAllocator allocator,
            ReadRecordsRequest readRecordsRequest,
            OutputStream outputStream)
            throws Exception
    {
        logger.info("doHandleRequest: request[{}]", ProtobufSerDe.PROTOBUF_JSON_PRINTER.print(readRecordsRequest));
        Message response = doReadRecords(allocator, readRecordsRequest);
        String jsonOut = ProtobufSerDe.PROTOBUF_JSON_PRINTER.print(response);
        logger.debug("ReadRecordsResponse json - {}", jsonOut);
        outputStream.write(jsonOut.getBytes());
    }

    /**
     * Used to read the row data associated with the provided Split.
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param request Details of the read request, including:
     * 1. The Split
     * 2. The Catalog, Database, and Table the read request is for.
     * 3. The filtering predicate (if any)
     * 4. The columns required for projection.
     * @return A RecordResponse which either a ReadRecordsResponse or a RemoteReadRecordsResponse containing the row
     * data for the requested Split.
     */
    public Message doReadRecords(BlockAllocator allocator, ReadRecordsRequest request)
            throws Exception
    {
        logger.info("doReadRecords: {}:{}", request.getSchema(), request.getSplit().getSpillLocation());
        SpillConfig spillConfig = getSpillConfig(request);
        try (ConstraintEvaluator evaluator = new ConstraintEvaluator(allocator,
                ProtobufMessageConverter.fromProtoSchema(allocator, request.getSchema()),
                ProtobufMessageConverter.fromProtoConstraints(allocator, request.getConstraints()));
                S3BlockSpiller spiller = new S3BlockSpiller(amazonS3, spillConfig, allocator, ProtobufMessageConverter.fromProtoSchema(allocator, request.getSchema()), evaluator, configOptions);
                QueryStatusChecker queryStatusChecker = new QueryStatusChecker(athena, athenaInvoker, request.getQueryId())
        ) {
            readWithConstraint(allocator, spiller, request, queryStatusChecker);

            if (!spiller.spilled()) {
                return ReadRecordsResponse.newBuilder()
                    .setType("ReadRecordsResponse")
                    .setCatalogName(request.getCatalogName())
                    .setRecords(ProtobufMessageConverter.toProtoBlock(spiller.getBlock()))
                    .build();
            }
            else {
                return RemoteReadRecordsResponse.newBuilder()
                    .setType("RemoteReadRecordsResponse")
                    .setCatalogName(request.getCatalogName())
                    .setSchema(request.getSchema())
                    .addAllRemoteBlocks(spiller.getSpillLocations().stream().map(ProtobufMessageConverter::toProtoSpillLocation).collect(Collectors.toList()))
                    .setEncryptionKey(ProtobufMessageConverter.toProtoEncryptionKey(spillConfig.getEncryptionKey()))
                    .build();
            }
        }
    }

    /**
     * A more stream lined option for reading the row data associated with the provided Split. This method differs from
     * doReadRecords(...) in that the SDK handles more of the request lifecycle, leaving you to focus more closely on
     * the task of actually reading from your source.
     *
     * @param spiller A BlockSpiller that should be used to write the row data associated with this Split.
     * The BlockSpiller automatically handles chunking the response, encrypting, and spilling to S3.
     * @param recordsRequest Details of the read request, including:
     * 1. The Split
     * 2. The Catalog, Database, and Table the read request is for.
     * 3. The filtering predicate (if any)
     * 4. The columns required for projection.
     * @param queryStatusChecker A QueryStatusChecker that you can use to stop doing work for a query that has already terminated
     * @note Avoid writing >10 rows per-call to BlockSpiller.writeRow(...) because this will limit the BlockSpiller's
     * ability to control Block size. The resulting increase in Block size may cause failures and reduced performance.
     */
    protected abstract void readWithConstraint(BlockAllocator allocator, BlockSpiller spiller, ReadRecordsRequest recordsRequest, QueryStatusChecker queryStatusChecker)
            throws Exception;

    protected SpillConfig getSpillConfig(ReadRecordsRequest request)
    {
        long maxBlockSize = request.getMaxBlockSize();
        if (configOptions.get(MAX_BLOCK_SIZE_BYTES) != null) {
            maxBlockSize = Long.parseLong(configOptions.get(MAX_BLOCK_SIZE_BYTES));
        }

        return SpillConfig.newBuilder()
                .withSpillLocation(ProtobufMessageConverter.fromProtoSplit(request.getSplit()).getSpillLocation())
                .withMaxBlockBytes(maxBlockSize)
                .withMaxInlineBlockBytes(request.getMaxInlineBlockSize())
                .withRequestId(request.getQueryId())
                .withEncryptionKey(ProtobufMessageConverter.fromProtoSplit(request.getSplit()).getEncryptionKey())
                .withNumSpillThreads(NUM_SPILL_THREADS)
                .build();
    }
}
