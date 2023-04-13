/*-
 * #%L
 * athena-hbase
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
package com.amazonaws.athena.connectors.hbase;

import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.data.BlockUtils;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.proto.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
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
import com.amazonaws.athena.connector.lambda.security.LocalKeyFactory;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufMessageConverter;
import com.amazonaws.athena.connectors.hbase.connection.HBaseConnection;
import com.amazonaws.athena.connectors.hbase.connection.HbaseConnectionFactory;
import com.amazonaws.athena.connectors.hbase.connection.ResultProcessor;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufSerDe.UNLIMITED_PAGE_SIZE_VALUE;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class HbaseMetadataHandlerTest
        extends TestBase
{
    private static final Logger logger = LoggerFactory.getLogger(HbaseMetadataHandlerTest.class);

    private HbaseMetadataHandler handler;
    private BlockAllocator allocator;

    @Rule
    public TestName testName = new TestName();

    @Mock
    private HBaseConnection mockClient;

    @Mock
    private HbaseConnectionFactory mockConnFactory;

    @Mock
    private AWSGlue awsGlue;

    @Mock
    private AWSSecretsManager secretsManager;

    @Mock
    private AmazonAthena athena;

    @Before
    public void setUp()
            throws Exception
    {
        logger.info("{}: enter", testName.getMethodName());
        handler = new HbaseMetadataHandler(awsGlue,
                new LocalKeyFactory(),
                secretsManager,
                athena,
                mockConnFactory,
                "spillBucket",
                "spillPrefix",
                com.google.common.collect.ImmutableMap.of(DEFAULT_CATALOG, "asdfCatalogConnection"));

        when(mockConnFactory.getOrCreateConn(nullable(String.class))).thenReturn(mockClient);

        allocator = new BlockAllocatorImpl();
    }

    @After
    public void tearDown()
            throws Exception
    {
        allocator.close();
        logger.info("{}: exit ", testName.getMethodName());
    }

    @Test
    public void doListSchemaNames()
            throws IOException
    {
        NamespaceDescriptor[] schemaNames = {NamespaceDescriptor.create("schema1").build(),
                NamespaceDescriptor.create("schema2").build(),
                NamespaceDescriptor.create("schema3").build()};

        when(mockClient.listNamespaceDescriptors()).thenReturn(schemaNames);

        ListSchemasRequest req = ListSchemasRequest.newBuilder().setIdentity(IDENTITY).setQueryId(QUERY_ID).setCatalogName(DEFAULT_CATALOG).build();
        ListSchemasResponse res = handler.doListSchemaNames(allocator, req);

        logger.info("doListSchemas - {}", res.getSchemasList());
        Set<String> expectedSchemaName = new HashSet<>();
        expectedSchemaName.add("schema1");
        expectedSchemaName.add("schema2");
        expectedSchemaName.add("schema3");
        assertEquals(expectedSchemaName, new HashSet<>(res.getSchemasList()));

        logger.info("doListSchemaNames: exit");
    }

    @Test
    public void doListTables()
            throws IOException
    {
        logger.info("doListTables - enter");

        String schema = "schema1";

        org.apache.hadoop.hbase.TableName[] tables = {
                org.apache.hadoop.hbase.TableName.valueOf("schema1", "table1"),
                org.apache.hadoop.hbase.TableName.valueOf("schema1", "table2"),
                org.apache.hadoop.hbase.TableName.valueOf("schema1", "table3")
        };

        Set<String> tableNames = new HashSet<>();
        tableNames.add("table1");
        tableNames.add("table2");
        tableNames.add("table3");

        when(mockClient.listTableNamesByNamespace(eq(schema))).thenReturn(tables);
        ListTablesRequest req = ListTablesRequest.newBuilder().setIdentity(IDENTITY).setQueryId(QUERY_ID).setCatalogName(DEFAULT_CATALOG).setSchemaName(schema).setPageSize(UNLIMITED_PAGE_SIZE_VALUE).build();
        ListTablesResponse res = handler.doListTables(allocator, req);
        logger.info("doListTables - {}", res.getTablesList());

        for (TableName next : res.getTablesList()) {
            assertEquals(schema, next.getSchemaName());
            assertTrue(tableNames.contains(next.getTableName()));
        }
        assertEquals(tableNames.size(), res.getTablesList().size());
    }

    /**
     * TODO: Add more types.
     */
    @Test
    public void doGetTable()
            throws Exception
    {
        List<Result> results = TestUtils.makeResults();

        ResultScanner mockScanner = mock(ResultScanner.class);
        when(mockScanner.iterator()).thenReturn(results.iterator());

        when(mockClient.scanTable(any(), nullable(Scan.class), any())).thenAnswer((InvocationOnMock invocationOnMock) -> {
            ResultProcessor processor = (ResultProcessor) invocationOnMock.getArguments()[2];
            return processor.scan(mockScanner);
        });

        GetTableRequest req = GetTableRequest.newBuilder().setIdentity(IDENTITY).setQueryId(QUERY_ID).setCatalogName(DEFAULT_CATALOG).setTableName(TABLE_NAME).build();
        GetTableResponse res = handler.doGetTable(allocator, req);
        logger.info("doGetTable - {}", res);

        Schema expectedSchema = TestUtils.makeSchema()
                .addField(HbaseSchemaUtils.ROW_COLUMN_NAME, Types.MinorType.VARCHAR.getType())
                .build();

        assertEquals(expectedSchema.getFields().size(), ProtobufMessageConverter.fromProtoSchema(allocator, res.getSchema()).getFields().size());
    }

    @Test
    public void doGetTableLayout()
            throws Exception
    {
        GetTableLayoutRequest req = GetTableLayoutRequest.newBuilder().setIdentity(IDENTITY).setQueryId(QUERY_ID).setCatalogName(DEFAULT_CATALOG).setTableName(TABLE_NAME).build();
        GetTableLayoutResponse res = handler.doGetTableLayout(allocator, req);

        logger.info("doGetTableLayout - {}", res);
        Block partitions = ProtobufMessageConverter.fromProtoBlock(allocator, res.getPartitions());
        for (int row = 0; row < partitions.getRowCount() && row < 10; row++) {
            logger.info("doGetTableLayout:{} {}", row, BlockUtils.rowToString(partitions, row));
        }

        assertTrue(partitions.getRowCount() > 0);
    }

    @Test
    public void doGetSplits()
            throws IOException
    {
        List<HRegionInfo> regionServers = new ArrayList<>();
        regionServers.add(TestUtils.makeRegion(1, "schema1", "table1"));
        regionServers.add(TestUtils.makeRegion(2, "schema1", "table1"));
        regionServers.add(TestUtils.makeRegion(3, "schema1", "table1"));
        regionServers.add(TestUtils.makeRegion(4, "schema1", "table1"));

        when(mockClient.getTableRegions(any())).thenReturn(regionServers);
        List<String> partitionCols = new ArrayList<>();

        Block partitions = BlockUtils.newBlock(allocator, "partitionId", Types.MinorType.INT.getType(), 0);

        String continuationToken = null;
        GetSplitsRequest req = GetSplitsRequest.newBuilder().setIdentity(IDENTITY).setQueryId(QUERY_ID).setCatalogName(DEFAULT_CATALOG).setTableName(TABLE_NAME).setPartitions(ProtobufMessageConverter.toProtoBlock(partitions)).addAllPartitionColumns(partitionCols).build();

        logger.info("doGetSplits: req[{}]", req);

        GetSplitsResponse response = handler.doGetSplits(allocator, req);
        continuationToken = response.getContinuationToken();

        logger.info("doGetSplits: continuationToken[{}] - numSplits[{}]",
                new Object[] {continuationToken, response.getSplitsList().size()});

        assertTrue("Continuation criteria violated", response.getSplitsList().size() == 4);
        assertFalse("Continuation criteria violated", response.hasContinuationToken());
    }
}
