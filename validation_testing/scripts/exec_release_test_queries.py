import boto3
import sys

athena_client = boto3.client('athena')

def run_query(query_string, results_location, workgroup_name, catalog, database):
    """
    Returns (query_id, succeeded)
    """
    query_id = start_query_execution(query_string, results_location, workgroup_name, catalog, database)
    finished_state = wait_until_query_finished(query_id)
    return query_id, finished_state == 'SUCCEEDED'


def start_query_execution(query_string, results_location, workgroup_name, catalog, database):
    start_query_resp = athena_client.start_query_execution(
        QueryString=query_string,
        ResultConfiguration={
            'OutputLocation': results_location},
        QueryExecutionContext={
            'Catalog': catalog,
            'Database': database,
        },
        WorkGroup=workgroup_name
    )
    return start_query_resp['QueryExecutionId']

def wait_until_query_finished(query_id):
    max_iterations = 100
    sleep_duration = 5
    query_execution, query_execution_status, query_execution_state = '', '', ''
    iterations = 0
    while not query_execution_state == 'FAILED' and not query_execution_state == 'SUCCEEDED' \
            and iterations < max_iterations:
        iterations += 1
        query_execution_response = athena_client.get_query_execution(
            QueryExecutionId=query_id)
        query_execution = query_execution_response['QueryExecution']
        query_execution_status = query_execution['Status']
        query_execution_state = query_execution_status['State']
        time.sleep(sleep_duration)
    if iterations >= max_iterations:
        return 'TIMED OUT'
    if query_execution_state == 'FAILED':
        print(query_execution)
        print(query_execution['Status']['AthenaError'])
    return query_execution_state

def test_ddl(connector_name, results_location, workgroup_name, catalog, database):
    create_view_query = f'''
        CREATE VIEW cx_view_{connector_name} AS
            SELECT c.c_customer_sk, c.c_customer_id, c.c_first_name, c.c_last_name, c.c_birth_year, c.c_email_address, ca.ca_address_sk, ca_street_number, ca_street_name
            FROM customer c JOIN customer_address ca
            ON c.c_current_addr_sk = ca.ca_address_sk
            WHERE c.c_birth_year = 1989; 
    '''
    select_from_view_query = f'SELECT *  FROM cx_view_{connector_name}'
    drop_view_query = f'DROP VIEW cx_view_{connector_name}'
    
    qid, succeeded = run_query(create_view_query, results_location, workgroup_name, catalog, database)
    if not succeeded:
        return False
    qid, succeeded = run_query(seelct_from_view_query, results_location, workgroup_name, catalog, database)
    if not succeeded:
        return False
    qid, succeeded = run_query(drop_view_query, results_location, workgroup_name, catalog, database)
    if not succeeded:
        return False
    return True

def test_dml(connector_name, results_location, workgroup_name, catalog, database):
    pass

def run_queries(connector_name):
    '''
    run all our release tests here.
    '''
    results_location = ''
    workgroup_name = ''
    catalog = ''
    database = ''
    test_ddl(connector_name, results_location, workgroup_name, catalog, database)
    test_dml(connector_name, results_location, workgroup_name, catalog, database))

if __name__ == '__main__':
    connector_name = sys.argv[1]
    run_queries(connector_name)
