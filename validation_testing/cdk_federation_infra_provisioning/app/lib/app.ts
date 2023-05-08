#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { RdsGenericStack } from './stacks/rds-generic-stack';
import { DynamoDBStack } from './stacks/dynamo-stack';
import { RedshiftStack } from './stacks/redshift-stack';
import { OpenSearchStack } from './stacks/opensearch-stack';
import * as rds from 'aws-cdk-lib/aws-rds'
import dotenv from 'dotenv';
dotenv.config();
const app = new cdk.App()

// this will cause failures if the .env file doesn't contain the variable DATABASE_PASSWORD. that is intentional.;
const database_password: string = process.env!.DATABASE_PASSWORD as string; 

// these names match the names of our connectors (athena-*) with the exception of opensearch, which is in development
const MYSQL_NAME: string = 'mysql'
const POSTGRES_NAME: string = 'postgresql'
const DYNAMO_DB_NAME: string = 'dynamodb'
const REDSHIFT_NAME: string = 'redshift'
const OPENSEARCH_NAME: string = 'opensearch'

// set this to whatever you want out of the TPCDS schema. For each table, we make a distinct glue job.
const tables: string[] = ['customer', 'customer_address'];


new RdsGenericStack(app, `${MYSQL_NAME}CdkStack`, {
  test_size_gigabytes: 10,
  db_type: MYSQL_NAME,
  db_port: 3306,
  s3_path: `s3://analytics-benchmark-test-data/warehouse/test1gb.db`,
  tpcds_tables: tables,
  password: database_password,
  connector_yaml_path: `../../../../../../athena-${MYSQL_NAME}/athena-${MYSQL_NAME}.yaml`
});

new RdsGenericStack(app, `${POSTGRES_NAME}CdkStack`, {
  test_size_gigabytes: 10,
  db_type: POSTGRES_NAME,
  db_port: 5432,
  s3_path: `s3://analytics-benchmark-test-data/warehouse/test1gb.db`,
  tpcds_tables: tables,
  password: database_password,
  connector_yaml_path: `../../../../../../athena-${POSTGRES_NAME}/athena-${POSTGRES_NAME}.yaml`
});

new DynamoDBStack(app, `${DYNAMO_DB_NAME}CdkStack`, {
  test_size_gigabytes: 1,
  s3_path: `s3://analytics-benchmark-test-data/warehouse/test1gb.db`,
  tpcds_tables: tables,
  password: database_password,
  connector_yaml_path: `../../../../../../athena-${DYNAMO_DB_NAME}/athena-${DYNAMO_DB_NAME}.yaml`
});

new RedshiftStack(app, `${REDSHIFT_NAME}CdkStack`, {
  test_size_gigabytes: 1,
  s3_path: `s3://analytics-benchmark-test-data/warehouse/test1gb.db`,
  tpcds_tables: tables,
  password: database_password,
  connector_yaml_path: `../../../../../../athena-${REDSHIFT_NAME}/athena-${REDSHIFT_NAME}.yaml`
}),
new OpenSearchStack(app, `${OPENSEARCH_NAME}CdkStack`, {
  test_size_gigabytes: 1,
  s3_path: `s3://analytics-benchmark-test-data/warehouse/test1gb.db`,
  tpcds_tables: tables,
  password: database_password,
  connector_yaml_path: `../../../../../../athena-elasticsearch/athena-elasticsearch.yaml`
});
