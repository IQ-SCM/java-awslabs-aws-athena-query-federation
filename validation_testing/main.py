import subprocess

TESTABLE_CONNECTORS = ['dynamodb']


def run_single_connector_release_tests(connector_name):
    shell_command = f'sh run_release_tests.sh {connector_name}'
    # check=True means we will throw an Exception if the subprocess exits with a non-zero response code.
    subprocess.run(shell_command, shell=True, check=True)

def run_all_connector_release_tests():
    for connector in TESTABLE_CONNECTORS:
        run_single_connector_release_tests(connector)

if __name__ == '__main__':
  run_all_connector_release_tests() 
