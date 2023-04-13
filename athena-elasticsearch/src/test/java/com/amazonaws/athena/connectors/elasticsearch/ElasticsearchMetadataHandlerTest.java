/*-
 * #%L
 * athena-example
 * %%
 * Copyright (C) 2019 Amazon Web Services
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
package com.amazonaws.athena.connectors.elasticsearch;

import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.data.BlockUtils;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.proto.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.proto.metadata.*;
import com.amazonaws.athena.connector.lambda.proto.security.FederatedIdentity;
import com.amazonaws.athena.connector.lambda.security.LocalKeyFactory;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufMessageConverter;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufSerDe.UNLIMITED_PAGE_SIZE_VALUE;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * This class is used to test the ElasticsearchMetadataHandler class.
 */
@RunWith(MockitoJUnitRunner.class)
public class ElasticsearchMetadataHandlerTest
{
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchMetadataHandlerTest.class);

    private ElasticsearchMetadataHandler handler;
    private boolean enableTests = System.getenv("publishing") != null &&
            System.getenv("publishing").equalsIgnoreCase("true");
    private BlockAllocatorImpl allocator;

    @Mock
    private AWSGlue awsGlue;

    @Mock
    private AWSSecretsManager awsSecretsManager;

    @Mock
    private AmazonAthena amazonAthena;

    @Mock
    private AwsRestHighLevelClient mockClient;

    @Mock
    private AwsRestHighLevelClientFactory clientFactory;

    @Mock
    private ElasticsearchDomainMapProvider domainMapProvider;

    @Before
    public void setUp()
    {
        logger.info("setUpBefore - enter");

        allocator = new BlockAllocatorImpl();
        when(clientFactory.getOrCreateClient(nullable(String.class))).thenReturn(mockClient);

        logger.info("setUpBefore - exit");
    }

    @After
    public void after()
    {
        allocator.close();
    }

    /**
     * Used to test the doListSchemaNames() functionality in the ElasticsearchMetadataHandler class.
     */
    @Test
    public void doListSchemaNames()
    {
        logger.info("doListSchemaNames - enter");

        // Generate hard-coded response with 3 domains.
        ListSchemasResponse mockDomains =
                ListSchemasResponse.newBuilder().setCatalogName("elasticsearch").addAllSchemas(ImmutableList.of("domain1", "domain2", "domain3")).build();

        // Get real response from doListSchemaNames().
        when(domainMapProvider.getDomainMap(null)).thenReturn(ImmutableMap.of("domain1", "endpoint1",
                "domain2", "endpoint2","domain3", "endpoint3"));

        handler = new ElasticsearchMetadataHandler(awsGlue, new LocalKeyFactory(), awsSecretsManager, amazonAthena,
                "spill-bucket", "spill-prefix", domainMapProvider, clientFactory, 10, com.google.common.collect.ImmutableMap.of());

        ListSchemasRequest req = ListSchemasRequest.newBuilder().setIdentity(fakeIdentity()).setQueryId("queryId").setCatalogName("elasticsearch").build();
        ListSchemasResponse realDomains = handler.doListSchemaNames(allocator, req);

        logger.info("doListSchemaNames - {}", realDomains.getSchemasList());

        // Test 1 - Real domain list should NOT be empty.
        assertFalse("Real domain list has no domain names!", realDomains.getSchemasList().isEmpty());
        // Test 2 - Real and mocked responses should have the same domains.
        assertTrue("Real and mocked domain responses have different domains!",
                domainsEqual(realDomains.getSchemasList(), mockDomains.getSchemasList()));

        logger.info("doListSchemaNames - exit");
    }

    /**
     * Used to assert that both real and mocked domain lists are equal.
     * @param list1 is a domain list to be compared.
     * @param list2 is a domain list to be compared.
     * @return true if the lists are equal, false otherwise.
     */
    private final boolean domainsEqual(Collection<String> list1, Collection<String> list2)
    {
        logger.info("domainsEqual - Enter - Domain1: {}, Domain2: {}", list1, list2);

        // lists must have the same number of domains.
        if (list1.size() != list2.size()) {
            logger.warn("Domain lists are different sizes!");
            return false;
        }

        // lists must have the same domains (irrespective of internal ordering).
        Iterator<String> iter = list1.iterator();
        while (iter.hasNext()) {
            if (!list2.contains(iter.next())) {
                logger.warn("Domain mismatch in list!");
                return false;
            }
        }

        return true;
    }

    /**
     * Used to test the doListTables() functionality in the ElasticsearchMetadataHandler class.
     * @throws IOException
     */
    @Test
    public void doListTables()
            throws Exception
    {
        logger.info("doListTables - enter");

        // Hardcoded response with 2 indices.
        Collection<TableName> mockIndices = ImmutableList.of(TableName.newBuilder().setSchemaName("movies").setTableName("customer").build(),
                TableName.newBuilder().setSchemaName("movies").setTableName("movies").build());

        // Get real indices.
        when(domainMapProvider.getDomainMap(null)).thenReturn(ImmutableMap.of("movies",
                "https://search-movies-ne3fcqzfipy6jcrew2wca6kyqu.us-east-1.es.amazonaws.com"));
        handler = new ElasticsearchMetadataHandler(awsGlue, new LocalKeyFactory(), awsSecretsManager, amazonAthena,
                "spill-bucket", "spill-prefix", domainMapProvider, clientFactory, 10, com.google.common.collect.ImmutableMap.of());
        when(mockClient.getAliases()).thenReturn(ImmutableSet.of("movies", ".kibana_1", "customer"));

        ListTablesRequest req = ListTablesRequest.newBuilder().setIdentity(fakeIdentity()).setQueryId("queryId").setCatalogName("elasticsearch").setSchemaName("movies").setPageSize(UNLIMITED_PAGE_SIZE_VALUE).build();
        Collection<TableName> realIndices = handler.doListTables(allocator, req).getTablesList();

        logger.info("doListTables - {}", realIndices);

        // Test 1 - Indices list should NOT be empty.
        assertFalse("Real indices list is empty!", realIndices.isEmpty());
        // Test 2 - Real list and mocked list should have the same indices.
        assertTrue("Real and mocked indices list are different!",
                indicesEqual(realIndices, mockIndices));

        logger.info("doListTables - exit");
    }

    /**
     * Used to assert that both real and mocked indices lists are equal.
     * @param list1 is an indices list to be compared.
     * @param list2 is an indices list to be compared.
     * @return true if the lists are equal, false otherwise.
     */
    private final boolean indicesEqual(Collection<TableName> list1, Collection<TableName> list2)
    {
        logger.info("indicesEqual - Enter - Index1: {}, Index2: {}", list1, list2);

        // lists must have the same number of indices.
        if (list1.size() != list2.size()) {
            logger.warn("Indices lists are different sizes!");
            return false;
        }

        // lists must have the same indices (irrespective of internal ordering).
        Iterator<TableName> iter = list1.iterator();
        while (iter.hasNext()) {
            if (!list2.contains(iter.next())) {
                logger.warn("Indices mismatch in list!");
                return false;
            }
        }

        return true;
    }

    /**
     * Used to test the doGetTable() functionality in the ElasticsearchMetadataHandler class.
     * @throws IOException
     */
    @Test
    public void doGetTable()
            throws Exception
    {
        logger.info("doGetTable - enter");

        // Mock mapping.
        Schema mockMapping = SchemaBuilder.newBuilder()
                .addField("mytext", Types.MinorType.VARCHAR.getType())
                .addField("mykeyword", Types.MinorType.VARCHAR.getType())
                .addField(new Field("mylong", FieldType.nullable(Types.MinorType.LIST.getType()),
                        Collections.singletonList(new Field("mylong",
                                FieldType.nullable(Types.MinorType.BIGINT.getType()), null))))
                .addField("myinteger", Types.MinorType.INT.getType())
                .addField("myshort", Types.MinorType.SMALLINT.getType())
                .addField("mybyte", Types.MinorType.TINYINT.getType())
                .addField("mydouble", Types.MinorType.FLOAT8.getType())
                .addField(new Field("myscaled",
                        new FieldType(true, Types.MinorType.BIGINT.getType(), null,
                                ImmutableMap.of("scaling_factor", "10.0")), null))
                .addField("myfloat", Types.MinorType.FLOAT4.getType())
                .addField("myhalf", Types.MinorType.FLOAT4.getType())
                .addField("mydatemilli", Types.MinorType.DATEMILLI.getType())
                .addField("mydatenano", Types.MinorType.DATEMILLI.getType())
                .addField("myboolean", Types.MinorType.BIT.getType())
                .addField("mybinary", Types.MinorType.VARCHAR.getType())
                .addField("mynested", Types.MinorType.STRUCT.getType(), ImmutableList.of(
                        new Field("l1long", FieldType.nullable(Types.MinorType.BIGINT.getType()), null),
                        new Field("l1date", FieldType.nullable(Types.MinorType.DATEMILLI.getType()), null),
                        new Field("l1nested", FieldType.nullable(Types.MinorType.STRUCT.getType()), ImmutableList.of(
                                new Field("l2short", FieldType.nullable(Types.MinorType.LIST.getType()),
                                        Collections.singletonList(new Field("l2short",
                                                FieldType.nullable(Types.MinorType.SMALLINT.getType()), null))),
                                new Field("l2binary", FieldType.nullable(Types.MinorType.VARCHAR.getType()),
                                        null))))).build();

        // Real mapping.
        LinkedHashMap<String, Object> mapping = new ObjectMapper().readValue(
                "{\n" +
                "  \"mishmash\" : {\n" +                                // Index: mishmash
                "    \"mappings\" : {\n" +
                "      \"_meta\" : {\n" +                               // _meta:
                "        \"mynested.l1nested.l2short\" : \"list\",\n" + // mynested.l1nested.l2short: LIST<SMALLINT>
                "        \"mylong\" : \"list\"\n" +                     // mylong: LIST<BIGINT>
                "      },\n" +
                "      \"properties\" : {\n" +
                "        \"mybinary\" : {\n" +                          // mybinary:
                "          \"type\" : \"binary\"\n" +                   // type: binary (VARCHAR)
                "        },\n" +
                "        \"myboolean\" : {\n" +                         // myboolean:
                "          \"type\" : \"boolean\"\n" +                  // type: boolean (BIT)
                "        },\n" +
                "        \"mybyte\" : {\n" +                            // mybyte:
                "          \"type\" : \"byte\"\n" +                     // type: byte (TINYINT)
                "        },\n" +
                "        \"mydatemilli\" : {\n" +                       // mydatemilli:
                "          \"type\" : \"date\"\n" +                     // type: date (DATEMILLI)
                "        },\n" +
                "        \"mydatenano\" : {\n" +                        // mydatenano:
                "          \"type\" : \"date_nanos\"\n" +               // type: date_nanos (DATEMILLI)
                "        },\n" +
                "        \"mydouble\" : {\n" +                          // mydouble:
                "          \"type\" : \"double\"\n" +                   // type: double (FLOAT8)
                "        },\n" +
                "        \"myfloat\" : {\n" +                           // myfloat:
                "          \"type\" : \"float\"\n" +                    // type: float (FLOAT4)
                "        },\n" +
                "        \"myhalf\" : {\n" +                            // myhalf:
                "          \"type\" : \"half_float\"\n" +               // type: half_float (FLOAT4)
                "        },\n" +
                "        \"myinteger\" : {\n" +                         // myinteger:
                "          \"type\" : \"integer\"\n" +                  // type: integer (INT)
                "        },\n" +
                "        \"mykeyword\" : {\n" +                         // mykeyword:
                "          \"type\" : \"keyword\"\n" +                  // type: keyword (VARCHAR)
                "        },\n" +
                "        \"mylong\" : {\n" +                            // mylong: LIST
                "          \"type\" : \"long\"\n" +                     // type: long (BIGINT)
                "        },\n" +
                "        \"mynested\" : {\n" +                          // mynested: STRUCT
                "          \"properties\" : {\n" +
                "            \"l1date\" : {\n" +                        // mynested.l1date:
                "              \"type\" : \"date_nanos\"\n" +           // type: date_nanos (DATEMILLI)
                "            },\n" +
                "            \"l1long\" : {\n" +                        // mynested.l1long:
                "              \"type\" : \"long\"\n" +                 // type: long (BIGINT)
                "            },\n" +
                "            \"l1nested\" : {\n" +                      // mynested.l1nested: STRUCT
                "              \"properties\" : {\n" +
                "                \"l2binary\" : {\n" +                  // mynested.l1nested.l2binary:
                "                  \"type\" : \"binary\"\n" +           // type: binary (VARCHAR)
                "                },\n" +
                "                \"l2short\" : {\n" +                   // mynested.l1nested.l2short: LIST
                "                  \"type\" : \"short\"\n" +            // type: short (SMALLINT)
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"myscaled\" : {\n" +                          // myscaled:
                "          \"type\" : \"scaled_float\",\n" +            // type: scaled_float (BIGINT)
                "          \"scaling_factor\" : 10.0\n" +               // factor: 10
                "        },\n" +
                "        \"myshort\" : {\n" +                           // myshort:
                "          \"type\" : \"short\"\n" +                    // type: short (SMALLINT)
                "        },\n" +
                "        \"mytext\" : {\n" +                            // mytext:
                "          \"type\" : \"text\"\n" +                     // type: text (VARCHAR)
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}\n", LinkedHashMap.class);
        LinkedHashMap<String, Object> index = (LinkedHashMap<String, Object>) mapping.get("mishmash");
        LinkedHashMap<String, Object> mappings = (LinkedHashMap<String, Object>) index.get("mappings");

        when(mockClient.getMapping(nullable(String.class))).thenReturn(mappings);

        // Get real mapping.
        when(domainMapProvider.getDomainMap(null)).thenReturn(ImmutableMap.of("movies",
                "https://search-movies-ne3fcqzfipy6jcrew2wca6kyqu.us-east-1.es.amazonaws.com"));
        handler = new ElasticsearchMetadataHandler(awsGlue, new LocalKeyFactory(), awsSecretsManager, amazonAthena,
                "spill-bucket", "spill-prefix", domainMapProvider, clientFactory, 10, com.google.common.collect.ImmutableMap.of());
        GetTableRequest req = GetTableRequest.newBuilder().setIdentity(fakeIdentity()).setQueryId("queryId").setCatalogName("elasticsearch").setTableName(TableName.newBuilder().setSchemaName("movies").setTableName("mishmash")).build();
        GetTableResponse res = handler.doGetTable(allocator, req);
        Schema realMapping = ProtobufMessageConverter.fromProtoSchema(allocator, res.getSchema());

        logger.info("doGetTable - {}", res);

        // Test1 - Real mapping must NOT be empty.
        assertTrue("Real mapping is empty!", realMapping.getFields().size() > 0);
        // Test2 - Real and mocked mappings must have the same fields.
        assertTrue("Real and mocked mappings are different!",
                ElasticsearchSchemaUtils.mappingsEqual(realMapping, mockMapping));

        logger.info("doGetTable - exit");
    }

    /**
     * Used to test the doGetSplits() functionality in the ElasticsearchMetadataHandler class.
     */
    @Test
    public void doGetSplits()
            throws Exception
    {
        logger.info("doGetSplits: enter");

        List<String> partitionCols = new ArrayList<>();

        Block partitions = BlockUtils.newBlock(allocator, "partitionId", Types.MinorType.INT.getType(), 0);

        String continuationToken = null;
        GetSplitsRequest req = GetSplitsRequest.newBuilder().setIdentity(fakeIdentity()).setQueryId("queryId").setCatalogName("elasticsearch").setTableName(TableName.newBuilder().setSchemaName("movies").setTableName("customer").build()).setPartitions(ProtobufMessageConverter.toProtoBlock(partitions)).addAllPartitionColumns(partitionCols).build();
        logger.info("doGetSplits: req[{}]", req);

        // Setup domain and endpoint
        String domain = "movies";
        String endpoint = "https://search-movies-ne3fcqzfipy6jcrew2wca6kyqu.us-east-1.es.amazonaws.com";
        when(domainMapProvider.getDomainMap(null)).thenReturn(ImmutableMap.of(domain, endpoint));

        when(mockClient.getShardIds(nullable(String.class), anyLong())).thenReturn(ImmutableSet
                .of(new Integer(0), new Integer(1), new Integer(2)));

        // Instantiate handler
        handler = new ElasticsearchMetadataHandler(awsGlue, new LocalKeyFactory(), awsSecretsManager, amazonAthena,
                "spill-bucket", "spill-prefix", domainMapProvider, clientFactory, 10, com.google.common.collect.ImmutableMap.of());

        // Call doGetSplits()
        GetSplitsResponse response = handler.doGetSplits(allocator, req);
        continuationToken = response.getContinuationToken();

        logger.info("doGetSplits: continuationToken[{}] - numSplits[{}]",
                new Object[] {continuationToken, response.getSplitsList().size()});

        // Response should contain 2 splits.
        assertEquals("Response has invalid number of splits", 3, response.getSplitsList().size());

        Set<String> shardIds = new HashSet<>(2);
        shardIds.add("_shards:0");
        shardIds.add("_shards:1");
        shardIds.add("_shards:2");
        response.getSplitsList().forEach(split -> {
            assertEquals(endpoint, split.getPropertiesMap().get(domain));
            String shard = split.getPropertiesMap().get(ElasticsearchMetadataHandler.SHARD_KEY);
            assertTrue("Split contains invalid shard: " + shard, shardIds.contains(shard));
            shardIds.remove(shard);
        });

        assertFalse("Continuation criteria violated", response.hasContinuationToken());

        logger.info("doGetSplits: exit");
    }

    private static FederatedIdentity fakeIdentity()
    {
        return FederatedIdentity.newBuilder().setArn("access_key_id").setAccount("principle").build();
    }

    @Test
    public void convertFieldTest()
    {
        logger.info("convertFieldTest: enter");

        handler = new ElasticsearchMetadataHandler(awsGlue, new LocalKeyFactory(), awsSecretsManager, amazonAthena,
                "spill-bucket", "spill-prefix", domainMapProvider, clientFactory, 10, com.google.common.collect.ImmutableMap.of());

        Field field = handler.convertField("myscaled", "SCALED_FLOAT(10.51)");

        assertEquals("myscaled", field.getName());
        assertEquals("10.51", field.getMetadata().get("scaling_factor"));

        field = handler.convertField("myscaledlist", "ARRAY<SCALED_FLOAT(100)>");

        assertEquals("myscaledlist", field.getName());
        assertEquals(Types.MinorType.LIST.getType(), field.getType());
        assertEquals("100", field.getChildren().get(0).getMetadata().get("scaling_factor"));

        field = handler.convertField("myscaledstruct", "STRUCT<myscaledstruct:SCALED_FLOAT(10.0)>");

        assertEquals(Types.MinorType.STRUCT.getType(), field.getType());
        assertEquals("myscaledstruct", field.getChildren().get(0).getName());
        assertEquals("10.0", field.getChildren().get(0).getMetadata().get("scaling_factor"));

        logger.info("convertFieldTest: exit");
    }
}
