/*-
 * #%L
 * Athena MSK Connector
 * %%
 * Copyright (C) 2019 - 2022 Amazon Web Services
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
package com.amazonaws.athena.connectors.msk;


import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.proto.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.proto.metadata.*;
import com.amazonaws.athena.connector.lambda.proto.security.FederatedIdentity;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufMessageConverter;
import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.glue.AWSGlueClientBuilder;
import com.amazonaws.services.glue.model.GetSchemaResult;
import com.amazonaws.services.glue.model.GetSchemaVersionResult;
import com.amazonaws.services.glue.model.ListRegistriesResult;
import com.amazonaws.services.glue.model.RegistryListItem;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*",
        "javax.management.*", "org.w3c.*", "javax.net.ssl.*", "sun.security.*", "jdk.internal.reflect.*", "javax.crypto.*", "javax.security.*"
})
@PrepareForTest({AWSSecretsManagerClientBuilder.class, AWSGlueClientBuilder.class, GlueRegistryReader.class})
public class AmazonMskMetadataHandlerTest {
    private static final String QUERY_ID = "queryId";
    private AmazonMskMetadataHandler amazonMskMetadataHandler;
    private BlockAllocator blockAllocator;
    private FederatedIdentity federatedIdentity;
    private Block partitions;
    private List<String> partitionCols;
    private Constraints constraints;

    @Mock
    AWSGlue awsGlue;

    MockConsumer<String, String> consumer;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        blockAllocator = new BlockAllocatorImpl();
        federatedIdentity = Mockito.mock(FederatedIdentity.class);
        partitions = Mockito.mock(Block.class);
        partitionCols = Mockito.mock(List.class);
        constraints = Mockito.mock(Constraints.class);
        java.util.Map configOptions = com.google.common.collect.ImmutableMap.of(
            "aws.region", "us-west-2",
            "glue_registry_arn", "arn:aws:glue:us-west-2:123456789101:registry/Athena-NEW",
            "auth_type", AmazonMskUtils.AuthType.SSL.toString(),
            "secret_manager_msk_creds_name", "testSecret",
            "kafka_endpoint", "12.207.18.179:9092",
            "certificates_s3_reference", "s3://msk-connector-test-bucket/mskfiles/",
            "secrets_manager_secret", "AmazonMSK_afq");

        consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Map<TopicPartition, Long> partitionsStart = new HashMap<>();
        Map<TopicPartition, Long> partitionsEnd = new HashMap<>();

        // max splits per request is 1000. Here we will make 1500 partitions that each have the max records
        // for a single split. we expect 1500 splits to generate, over two requests.
        for (int i = 0; i < 1500; i++) {
            partitionsStart.put(new TopicPartition("testTopic", i), 0L);
            partitionsEnd.put(new TopicPartition("testTopic",  i), com.amazonaws.athena.connectors.msk.AmazonMskConstants.MAX_RECORDS_IN_SPLIT - 1L); // keep simple and don't have multiple pieces
        }
        List<PartitionInfo> partitionInfoList = new ArrayList<>(partitionsStart.keySet())
                .stream()
                .map(it -> new PartitionInfo(it.topic(), it.partition(), null, null, null))
                .collect(Collectors.toList());
        consumer.updateBeginningOffsets(partitionsStart);
        consumer.updateEndOffsets(partitionsEnd);
        consumer.updatePartitions("testTopic", partitionInfoList);

        amazonMskMetadataHandler = new AmazonMskMetadataHandler(consumer, configOptions);
    }

    @After
    public void tearDown() {
        blockAllocator.close();
    }

    @Test
    public void testDoListSchemaNames() {
        PowerMockito.mockStatic(AWSGlueClientBuilder.class);
        PowerMockito.when(AWSGlueClientBuilder.defaultClient()).thenReturn(awsGlue);
        PowerMockito.when(awsGlue.listRegistries(any())).thenAnswer(x -> (new ListRegistriesResult()).withRegistries(
          (new RegistryListItem()).withRegistryName("Asdf").withDescription("something something {AthenaFederationMSK} something"))
        );

        ListSchemasRequest listSchemasRequest = ListSchemasRequest.newBuilder().setIdentity(federatedIdentity).setQueryId(QUERY_ID).setCatalogName("default").build();
        ListSchemasResponse listSchemasResponse = amazonMskMetadataHandler.doListSchemaNames(blockAllocator, listSchemasRequest);

        assertEquals(new ArrayList(com.google.common.collect.ImmutableList.of("Asdf")), new ArrayList(listSchemasResponse.getSchemasList()));
    }

    @Test(expected = RuntimeException.class)
    public void testDoListSchemaNamesThrowsException() {
        ListSchemasRequest listSchemasRequest = mock(ListSchemasRequest.class);
        when(listSchemasRequest.getCatalogName()).thenThrow(new RuntimeException("RuntimeException() "));
        ListSchemasResponse listSchemasResponse = amazonMskMetadataHandler.doListSchemaNames(blockAllocator, listSchemasRequest);
        assertNull(listSchemasResponse);
    }


    @Test
    public void testDoGetTable() throws Exception {
        String arn = "defaultarn", schemaName = "defaultschemaname", schemaVersionId = "defaultversionid";
        Long latestSchemaVersion = 123L;
        GetSchemaResult getSchemaResult = new GetSchemaResult();
        GetSchemaVersionResult getSchemaVersionResult = new GetSchemaVersionResult();
        getSchemaResult.setSchemaArn(arn);
        getSchemaResult.setSchemaName(schemaName);
        getSchemaResult.setLatestSchemaVersion(latestSchemaVersion);
        getSchemaVersionResult.setSchemaArn(arn);
        getSchemaVersionResult.setSchemaVersionId(schemaVersionId);
        getSchemaVersionResult.setSchemaDefinition("{\n" +
                "\t\"topicName\": \"testtable\",\n" +
                "\t\"message\": {\n" +
                "\t\t\"dataFormat\": \"json\",\n" +
                "\t\t\"fields\": [{\n" +
                "\t\t\t\"name\": \"intcol\",\n" +
                "\t\t\t\"mapping\": \"intcol\",\n" +
                "\t\t\t\"type\": \"INTEGER\"\n" +
                "\t\t}]\n" +
                "\t}\n" +
                "}");
        PowerMockito.mockStatic(AWSGlueClientBuilder.class);
        PowerMockito.when(AWSGlueClientBuilder.defaultClient()).thenReturn(awsGlue);
        PowerMockito.when(awsGlue.getSchema(any())).thenReturn(getSchemaResult);
        PowerMockito.when(awsGlue.getSchemaVersion(any())).thenReturn(getSchemaVersionResult);
        GetTableRequest getTableRequest = GetTableRequest.newBuilder().setIdentity(federatedIdentity).setQueryId(QUERY_ID).setCatalogName("kafka").setTableName(TableName.newBuilder().setSchemaName("default").setTableName("testtable")).build();
        GetTableResponse getTableResponse = amazonMskMetadataHandler.doGetTable(blockAllocator, getTableRequest);
        assertEquals(1, ProtobufMessageConverter.fromProtoSchema(blockAllocator, getTableResponse.getSchema()).getFields().size());
    }

    @Test
    public void testDoGetSplits() throws Exception
    {
        String arn = "defaultarn", schemaName = "defaultschemaname", schemaVersionId = "defaultversionid";
        Long latestSchemaVersion = 123L;
        GetSchemaResult getSchemaResult = new GetSchemaResult();
        GetSchemaVersionResult getSchemaVersionResult = new GetSchemaVersionResult();
        getSchemaResult.setSchemaArn(arn);
        getSchemaResult.setSchemaName(schemaName);
        getSchemaResult.setLatestSchemaVersion(latestSchemaVersion);
        getSchemaVersionResult.setSchemaArn(arn);
        getSchemaVersionResult.setSchemaVersionId(schemaVersionId);
        getSchemaVersionResult.setSchemaDefinition("{\n" +
                "\t\"topicName\": \"testTopic\",\n" +
                "\t\"message\": {\n" +
                "\t\t\"dataFormat\": \"json\",\n" +
                "\t\t\"fields\": [{\n" +
                "\t\t\t\"name\": \"intcol\",\n" +
                "\t\t\t\"mapping\": \"intcol\",\n" +
                "\t\t\t\"type\": \"INTEGER\"\n" +
                "\t\t}]\n" +
                "\t}\n" +
                "}");
        PowerMockito.mockStatic(AWSGlueClientBuilder.class);
        PowerMockito.when(AWSGlueClientBuilder.defaultClient()).thenReturn(awsGlue);
        PowerMockito.when(awsGlue.getSchema(any())).thenReturn(getSchemaResult);
        PowerMockito.when(awsGlue.getSchemaVersion(any())).thenReturn(getSchemaVersionResult);

        GetSplitsRequest request = GetSplitsRequest.newBuilder().setIdentity(federatedIdentity).setQueryId(QUERY_ID).setCatalogName("kafka").setTableName(TableName.newBuilder().setSchemaName("default").setTableName("testTopic").build()).setPartitions(ProtobufMessageConverter.toProtoBlock(Mockito.mock(Block.class))).addAllPartitionCols(new ArrayList<>()).setConstraints(ProtobufMessageConverter.toProtoConstraints(Mockito.mock(Constraints.class))).build();;

        GetSplitsResponse response = amazonMskMetadataHandler.doGetSplits(blockAllocator, request);
        assertEquals(1000, response.getSplitsList().size());
        assertEquals("1000", response.getContinuationToken());
        request = GetSplitsRequest.newBuilder().setIdentity(federatedIdentity).setQueryId(QUERY_ID).setCatalogName("kafka").setTableName(TableName.newBuilder().setSchemaName("default").setTableName("testTopic").build()).setPartitions(ProtobufMessageConverter.toProtoBlock(Mockito.mock(Block.class))).addAllPartitionCols(new ArrayList<>()).setConstraints(ProtobufMessageConverter.toProtoConstraints(Mockito.mock(Constraints.class))).setContinuationToken(response.getContinuationToken()).build();
        response = amazonMskMetadataHandler.doGetSplits(blockAllocator, request);
        assertEquals(500, response.getSplitsList().size());
        assertNull(response.getContinuationToken());
    }
}
