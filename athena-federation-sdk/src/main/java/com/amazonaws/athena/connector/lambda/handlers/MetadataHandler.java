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
import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockUtils;
import com.amazonaws.athena.connector.lambda.data.BlockWriter;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.data.SimpleBlockWriter;
import com.amazonaws.athena.connector.lambda.data.SupportedTypes;
import com.amazonaws.athena.connector.lambda.domain.predicate.ConstraintEvaluator;
import com.amazonaws.athena.connector.lambda.proto.domain.spill.SpillLocation;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableLayoutRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableLayoutResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListSchemasRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListSchemasResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListTablesRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListTablesResponse;
import com.amazonaws.athena.connector.lambda.proto.request.PingRequest;
import com.amazonaws.athena.connector.lambda.proto.request.PingResponse;
import com.amazonaws.athena.connector.lambda.proto.request.TypeHeader;
import com.amazonaws.athena.connector.lambda.proto.security.EncryptionKey;
import com.amazonaws.athena.connector.lambda.security.CachableSecretsManager;
import com.amazonaws.athena.connector.lambda.security.EncryptionKeyFactory;
import com.amazonaws.athena.connector.lambda.security.KmsKeyFactory;
import com.amazonaws.athena.connector.lambda.security.LocalKeyFactory;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufMessageConverter;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufSerDe;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufUtils;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.athena.AmazonAthenaClientBuilder;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

import static com.amazonaws.athena.connector.lambda.handlers.AthenaExceptionFilter.ATHENA_EXCEPTION_FILTER;
import static com.amazonaws.athena.connector.lambda.handlers.FederationCapabilities.CAPABILITIES;
import static com.amazonaws.athena.connector.lambda.handlers.SerDeVersion.SERDE_VERSION;

/**
 * This class defines the functionality required by any valid source of federated metadata for Athena. It is recommended
 * that all connectors extend this class for Metadata operations though it is possible for you to write your own
 * from the ground up as long as you satisfy the wire protocol. For all cases we've encountered it has made more sense
 * to start with this base class and use it's implementation for most of the boilerplate related to Lambda and resource
 * lifecycle so we could focus on the task of integrating with the source we were interested in.
 *
 * @note All schema names, table names, and column names must be lower case at this time. Any entities that are uppercase or
 * mixed case will not be accessible in queries and will be lower cased by Athena's engine to ensure consistency across
 * sources. As such you may need to handle this when integrating with a source that supports mixed case. As an example,
 * you can look at the CloudwatchTableResolver in the athena-cloudwatch module for one potential approach to this challenge.
 */
public abstract class MetadataHandler
{
    private static final Logger logger = LoggerFactory.getLogger(MetadataHandler.class);

    // When MetadataHandler is used as a Lambda, configOptions is the same as System.getenv()
    // Otherwise in situations where the connector is used outside of a Lambda, it may be a config map
    // that is passed in.
    protected final java.util.Map<String, String> configOptions;

    //name of the default column used when a default single-partition response is required for connectors that
    //do not support robust partitioning. In such cases Athena requires at least 1 partition in order indicate
    //there is indeed data to be read vs. queries that were able to fully partition prune and thus decide there
    //was no data to read.
    private static final String PARTITION_ID_COL = "partitionId";
    //The value that denotes encryption should be disabled, encryption is enabled by default.
    private static final String DISABLE_ENCRYPTION = "true";
    //The default S3 prefix to use when spilling to S3
    private static final String DEFAULT_SPILL_PREFIX = "athena-federation-spill";
    protected static final String SPILL_BUCKET_ENV = "spill_bucket";
    protected static final String SPILL_PREFIX_ENV = "spill_prefix";
    protected static final String KMS_KEY_ID_ENV = "kms_key_id";
    protected static final String DISABLE_SPILL_ENCRYPTION = "disable_spill_encryption";
    protected static final String FUNCTION_ARN_CONFIG_KEY = "FUNCTION_ARN";
    private final CachableSecretsManager secretsManager;
    private final AmazonAthena athena;
    private final ThrottlingInvoker athenaInvoker;
    private final EncryptionKeyFactory encryptionKeyFactory;
    private final String spillBucket;
    private final String spillPrefix;
    private final String sourceType;

    /**
     * When MetadataHandler is used as a Lambda, the "Main" class will pass in System.getenv() as the configOptions.
     * Otherwise in situations where the connector is used outside of a Lambda, it may be a config map
     * that is passed in.
     * @param sourceType Used to aid in logging diagnostic info when raising a support case.
     */
    public MetadataHandler(String sourceType, java.util.Map<String, String> configOptions)
    {
        this.configOptions = configOptions;
        this.sourceType = sourceType;
        this.spillBucket = this.configOptions.get(SPILL_BUCKET_ENV);
        this.spillPrefix = this.configOptions.getOrDefault(SPILL_PREFIX_ENV, DEFAULT_SPILL_PREFIX);

        if (DISABLE_ENCRYPTION.equalsIgnoreCase(this.configOptions.getOrDefault(DISABLE_SPILL_ENCRYPTION, "false"))) {
            this.encryptionKeyFactory = null;
        }
        else {
            this.encryptionKeyFactory = (this.configOptions.get(KMS_KEY_ID_ENV) != null) ?
                    new KmsKeyFactory(AWSKMSClientBuilder.standard().build(), this.configOptions.get(KMS_KEY_ID_ENV)) :
                    new LocalKeyFactory();
        }

        this.secretsManager = new CachableSecretsManager(AWSSecretsManagerClientBuilder.defaultClient());
        this.athena = AmazonAthenaClientBuilder.defaultClient();
        this.athenaInvoker = ThrottlingInvoker.newDefaultBuilder(ATHENA_EXCEPTION_FILTER, configOptions).build();
    }

    /**
     * @param sourceType Used to aid in logging diagnostic info when raising a support case.
     */
    public MetadataHandler(
        EncryptionKeyFactory encryptionKeyFactory,
        AWSSecretsManager secretsManager,
        AmazonAthena athena,
        String sourceType,
        String spillBucket,
        String spillPrefix,
        java.util.Map<String, String> configOptions)
    {
        this.configOptions = configOptions;
        this.encryptionKeyFactory = encryptionKeyFactory;
        this.secretsManager = new CachableSecretsManager(secretsManager);
        this.athena = athena;
        this.sourceType = sourceType;
        this.spillBucket = spillBucket;
        this.spillPrefix = spillPrefix;
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

    protected EncryptionKey makeEncryptionKey()
    {
        return (encryptionKeyFactory != null) ? encryptionKeyFactory.create() : null;
    }

        /**
     * Used to make a spill location for a split. Each split should have a unique spill location, so be sure
     * to call this method once per split!
     * @param queryId 
     * @return A unique spill location.
     */
    protected SpillLocation makeSpillLocation(String queryId)
    {
        return SpillLocation.newBuilder()
                .setBucket(spillBucket)
                .setKey(ProtobufUtils.buildS3SpillLocationKey(spillPrefix, queryId, UUID.randomUUID().toString()))
                .setDirectory(true) // this is true because our key is a nested path
                .build();
    }

    protected final void doHandleRequest(BlockAllocator allocator,
            TypeHeader typeHeader,
            String inputJson,
            OutputStream outputStream)
            throws Exception
    {
        logger.info("doHandleRequest: request[{}]", inputJson);

        // we have to support Java 8, or else we would be able to use Switch case pattern matching + return values to make this nicer.
        switch(typeHeader.getType()) {
            case "ListSchemasRequest":
                ListSchemasRequest listSchemasRequest = (ListSchemasRequest) ProtobufSerDe.buildFromJson(inputJson, ListSchemasRequest.newBuilder());
                ListSchemasResponse listSchemasResponse = doListSchemaNames(allocator, listSchemasRequest);
                if (!listSchemasResponse.hasType()) {
                    listSchemasResponse = listSchemasResponse.toBuilder().setType("ListSchemasResponse").build();
                } 
                ProtobufSerDe.writeResponse(listSchemasResponse, outputStream);
                return;
            case "ListTablesRequest":
                ListTablesRequest listTablesRequest = (ListTablesRequest) ProtobufSerDe.buildFromJson(inputJson, ListTablesRequest.newBuilder());
                ListTablesResponse listTablesResponse = doListTables(allocator, listTablesRequest);
                if (!listTablesResponse.hasType()) {
                    listTablesResponse = listTablesResponse.toBuilder().setType("ListTablesResponse").build();
                } 
                ProtobufSerDe.writeResponse(listTablesResponse, outputStream);
                return;
            case "GetTableRequest":
                GetTableRequest getTableRequest = (GetTableRequest) ProtobufSerDe.buildFromJson(inputJson, GetTableRequest.newBuilder());
                GetTableResponse getTableResponse = doGetTable(allocator, getTableRequest);
                if (!getTableResponse.hasType()) {
                    getTableResponse = getTableResponse.toBuilder().setType("GetTableResponse").build();
                }
                assertTypes(ProtobufMessageConverter.fromProtoSchema(allocator, getTableResponse.getSchema()));
                ProtobufSerDe.writeResponse(getTableResponse, outputStream);
                return;
            case "GetTableLayoutRequest":
                GetTableLayoutRequest getTableLayoutRequest = (GetTableLayoutRequest) ProtobufSerDe.buildFromJson(inputJson, GetTableLayoutRequest.newBuilder());
                GetTableLayoutResponse getTableLayoutResponse = doGetTableLayout(allocator, getTableLayoutRequest);
                if (!getTableLayoutResponse.hasType()) {
                    getTableLayoutResponse = getTableLayoutResponse.toBuilder().setType("GetTableLayoutResponse").build();
                }
                ProtobufSerDe.writeResponse(getTableLayoutResponse, outputStream);
                return;
            case "GetSplitsRequest":
                GetSplitsRequest getSplitsRequest = (GetSplitsRequest) ProtobufSerDe.buildFromJson(inputJson, GetSplitsRequest.newBuilder());
                GetSplitsResponse getSplitsResponse = doGetSplits(allocator, getSplitsRequest);
                if (!getSplitsResponse.hasType()) {
                    getSplitsResponse = getSplitsResponse.toBuilder().setType("GetSplitsResponse").build();
                }
                ProtobufSerDe.writeResponse(getSplitsResponse, outputStream);
                return;
            default:
              throw new UnsupportedOperationException("Input type is not recognized - " + typeHeader.getType());
        }
    }

    /**
     * Used to get the list of schemas (aka databases) that this source contains.
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param request Provides details on who made the request and which Athena catalog they are querying.
     * @return A ListSchemasResponse which primarily contains a Set<String> of schema names and a catalog name
     * corresponding the Athena catalog that was queried.
     */
    public abstract ListSchemasResponse doListSchemaNames(final BlockAllocator allocator, final ListSchemasRequest request)
            throws Exception;

    /**
     * Used to get the list of tables that this source contains.
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param request Provides details on who made the request and which Athena catalog and database they are querying.
     * @return A ListTablesResponse which primarily contains a List<TableName> enumerating the tables in this
     * catalog, database tuple. It also contains the catalog name corresponding the Athena catalog that was queried.
     */
    public abstract ListTablesResponse doListTables(final BlockAllocator allocator, final ListTablesRequest request)
            throws Exception;

    /**
     * Used to get definition (field names, types, descriptions, etc...) of a Table.
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param request Provides details on who made the request and which Athena catalog, database, and table they are querying.
     * @return A GetTableResponse which primarily contains:
     * 1. An Apache Arrow Schema object describing the table's columns, types, and descriptions.
     * 2. A Set<String> of partition column names (or empty if the table isn't partitioned).
     */
    public abstract GetTableResponse doGetTable(final BlockAllocator allocator, final GetTableRequest request)
            throws Exception;

    /**
     * Used to get the partitions that must be read from the request table in order to satisfy the requested predicate.
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param request Provides details of the catalog, database, and table being queried as well as any filter predicate.
     * @return A GetTableLayoutResponse which primarily contains:
     * 1. An Apache Arrow Block with 0 or more partitions to read. 0 partitions implies there are 0 rows to read.
     * 2. Set<String> of partition column names which should correspond to columns in your Apache Arrow Block.
     * @note Partitions are opaque to Amazon Athena in that it does not understand their contents, just that it must call
     * doGetSplits(...) for each partition you return in order to determine which reads to perform and if those reads
     * can be parallelized. This means the contents of this response are more for you than they are for Athena.
     * @note Partitions are partially opaque to Amazon Athena in that it only understands your partition columns and
     * how to filter out partitions that do not meet the query's constraints. Any additional columns you add to the
     * partition data are ignored by Athena but passed on to calls on GetSplits.
     */
    public GetTableLayoutResponse doGetTableLayout(final BlockAllocator allocator, final GetTableLayoutRequest request)
            throws Exception
    {
        SchemaBuilder constraintSchema = new SchemaBuilder().newBuilder();
        SchemaBuilder partitionSchemaBuilder = new SchemaBuilder().newBuilder();

        /**
         * Add our partition columns to the response schema so the engine knows how to interpret the list of
         * partitions we are going to return.
         */
        Schema requestSchema = ProtobufMessageConverter.fromProtoSchema(allocator, request.getSchema());
        for (String nextPartCol : request.getPartitionColsList()) {
            Field partitionCol = requestSchema.findField(nextPartCol);
            partitionSchemaBuilder.addField(nextPartCol, partitionCol.getType());
            constraintSchema.addField(nextPartCol, partitionCol.getType());
        }

        enhancePartitionSchema(allocator, partitionSchemaBuilder, request);
        Schema partitionSchema = partitionSchemaBuilder.build();

        if (partitionSchema.getFields().isEmpty() && partitionSchema.getCustomMetadata().isEmpty()) {
            //Even though our table doesn't support complex layouts, partitioning or metadata, we need to convey that there is at least
            //1 partition to read as part of the query or Athena will assume partition pruning found no candidate layouts to read.
            Block partitions = BlockUtils.newBlock(allocator, PARTITION_ID_COL, Types.MinorType.INT.getType(), 1);
            return GetTableLayoutResponse.newBuilder()
                .setType("GetTableLayoutResponse")
                .setCatalogName(request.getCatalogName())
                .setTableName(request.getTableName())
                .setPartitions(ProtobufMessageConverter.toProtoBlock(partitions))
                .build();
        }

        /**
         * Now use the constraint that was in the request to do some partition pruning. Here we are just
         * generating some fake values for the partitions but in a real implementation you'd use your metastore
         * or knowledge of the actual table's physical layout to do this.
         */
        try (ConstraintEvaluator constraintEvaluator = new ConstraintEvaluator(allocator,
                constraintSchema.build(),
                ProtobufMessageConverter.fromProtoConstraints(allocator, request.getConstraints()));
                QueryStatusChecker queryStatusChecker = new QueryStatusChecker(athena, athenaInvoker, request.getQueryId())
        ) {
            Block partitions = allocator.createBlock(partitionSchemaBuilder.build());
            partitions.constrain(constraintEvaluator);
            SimpleBlockWriter blockWriter = new SimpleBlockWriter(partitions);
            getPartitions(allocator, blockWriter, request, queryStatusChecker);
            return GetTableLayoutResponse.newBuilder()
                .setType("GetTableLayoutResponse")
                .setCatalogName(request.getCatalogName())
                .setTableName(request.getTableName())
                .setPartitions(ProtobufMessageConverter.toProtoBlock(partitions))
                .build();
        }
    }

    /**
     * This method can be used to add additional fields to the schema of our partition response. Athena
     * expects each partitions in the response to have a column corresponding to your partition columns.
     * You can choose to add additional columns to that response which Athena will ignore but will pass
     * on to you when it call GetSplits(...) for each partition.
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param partitionSchemaBuilder The SchemaBuilder you can use to add additional columns and metadata to the
     * partitions response.
     * @param request The GetTableLayoutResquest that triggered this call.
     */
    public void enhancePartitionSchema(BlockAllocator allocator, SchemaBuilder partitionSchemaBuilder, GetTableLayoutRequest request)
    {
        //You can add additional fields to the partition schema which are ignored by Athena
        //but will be passed on to called to GetSplits(...). This can be handy when you
        //want to avoid extra round trips to your metastore. For example, when you generate
        //the partition list you may have easy access to the storage details (e.g. S3 location)
        //of the partition. Athena doesn't need the S3 location but when Athena calls you
        //to generate the Splits for the partition, having the S3 location would save you
        //extra work. For that reason you can add a field to the partition schema which
        //contains the s3 location.
    }

    /**
     * Used to get the partitions that must be read from the request table in order to satisfy the requested predicate.
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param blockWriter Used to write rows (partitions) into the Apache Arrow response.
     * @param request Provides details of the catalog, database, and table being queried as well as any filter predicate.
     * @param queryStatusChecker A QueryStatusChecker that you can use to stop doing work for a query that has already terminated
     * @note Partitions are partially opaque to Amazon Athena in that it only understands your partition columns and
     * how to filter out partitions that do not meet the query's constraints. Any additional columns you add to the
     * partition data are ignored by Athena but passed on to calls on GetSplits. Also note tat the BlockWriter handlers
     * automatically constraining and filtering out values that don't satisfy the query's predicate. This is how we
     * we accomplish partition pruning. You can optionally retreive a ConstraintEvaluator from BlockWriter if you have
     * your own need to apply filtering in Lambda. Otherwise you can get the actual preducate from the request object
     * for pushing down into the source you are querying.
     */
    public abstract void getPartitions(final BlockAllocator allocator, final BlockWriter blockWriter,
            final GetTableLayoutRequest request, QueryStatusChecker queryStatusChecker)
            throws Exception;

    /**
     * Used to split-up the reads required to scan the requested batch of partition(s).
     *
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param request Provides details of the catalog, database, table, andpartition(s) being queried as well as
     * any filter predicate.
     * @return A GetSplitsResponse which primarily contains:
     * 1. A Set<Split> which represent read operations Amazon Athena must perform by calling your read function.
     * 2. (Optional) A continuation token which allows you to paginate the generation of splits for large queries.
     * @note A Split is a mostly opaque object to Amazon Athena. Amazon Athena will use the optional SpillLocation and
     * optional EncryptionKey for pipelined reads but all properties you set on the Split are passed to your read
     * function to help you perform the read.
     */
    public abstract GetSplitsResponse doGetSplits(BlockAllocator allocator, GetSplitsRequest request)
            throws Exception;

    /**
     * Used to warm up your function as well as to discovery its capabilities (e.g. SDK capabilities)
     *
     * @param request The PingRequest.
     * @return A PingResponse.
     * @throws IOException
     * @note We do not recommend modifying this function, instead you should implement doPing(...)
     */
    public void doPing(PingRequest request, OutputStream outputStream) throws IOException
    {
        PingResponse pingResponse = PingResponse.newBuilder()
            .setType("PingResponse")
            .setCatalogName(request.getCatalogName())
            .setQueryId(request.getQueryId())
            .setSourceType(sourceType)
            .setCapabilities(CAPABILITIES)
            .setSerDeVersion(SERDE_VERSION)
            .build();
        try {
            onPing(request);
        }
        catch (Exception ex) {
            logger.warn("doPing: encountered an exception while delegating onPing.", ex);
        }
        ProtobufSerDe.writeResponse(pingResponse, outputStream);
    }

    /**
     * Provides you a signal that can be used to warm up your function.
     *
     * @param request The PingRequest.
     */
    public void onPing(PingRequest request)
    {
        //NoOp
    }

    /**
     * Helper function that is used to enforced we only return supported types.
     *
     * @param response The response to check.
     * @note We check GetTableResponse because this is the gate-keeper to all other requests. If you can only
     * discover valid (supported) schemas, then it follows that it would be difficult to develop a connector
     * which unknowingly returns unsupported types.
     */
    private void assertTypes(Schema schema)
    {
        for (Field next : schema.getFields()) {
            SupportedTypes.assertSupported(next);
        }
    }
}
