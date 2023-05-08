# Script just handles calling other scripts in the right order and will exit if any of them fail.
# Performs the following steps:
#   - Build and deploy CDK stacks for each of the connectors we are testing against
#   - Once the stacks finish deploying, start and wait for each glue job to finish
#   - Once those are done, invoke Athena queries against our test data
#   - Once those are done, exit successfully.

# To keep things simple to start, we'll only involve DynamoDB in this process. The other stacks will come later.


# PREREQS. need env var $REPOSITORY_ROOT and aws credentials exported.
# Expects input arg of a connector name
CONNECTOR_NAME=$1
VALIDATION_TESTING_ROOT=$REPOSITORY_ROOT/validation_testing
mkdir -p ~/docker_images/
curl -s https://raw.githubusercontent.com/henrymai/container_env/master/env.sh | sed '/--gpus/d' > ~/docker_images/gh_env.sh
chmod 0755 ~/docker_images/gh_env.sh;

# make current environment variables sourceable for container env
cat <<EOF > $VALIDATION_TESTING_ROOT/env_vars.sh
export AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID
export AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY
export AWS_SESSION_TOKEN=$AWS_SESSION_TOKEN
export AWS_DEFAULT_REGION=us-east-1
export REPOSITORY_ROOT=$REPOSITORY_ROOT
export CONNECTOR_NAME=$CONNECTOR_NAME
EOF

# upload connector jar to s3 and update yaml to s3 uri
aws s3 cp $REPOSITORY_ROOT/athena-$CONNECTOR_NAME/target/athena-$CONNECTOR_NAME-2022.47.1.jar s3://athena-federation-validation-testing-jars
sed -i 's#CodeUri: "./target/athena-$CONNECTOR_NAME-2022.47.1.jar"#CodeUri: "s3://athena-federation-validation-testing-jars/athena-$CONNECTOR_NAME-2022.47.1.jar"#' $REPOSITORY_ROOT/athena-$CONNECTOR_NAME/athena-$CONNECTOR_NAME.yaml


# then we can deploy the stack
sh $(dirname $(find . -name ATHENA_INFRA_SPINUP_ROOT))/docker_image/build.sh
IMAGE=federation-cdk-dev ~/docker_images/gh_env.sh '\
  source env_vars.sh;
  cd $(dirname $(find . -name ATHENA_INFRA_SPINUP_ROOT))/app;
  npm install;
  npm run build;
  npm run cdk synth;
  npm run cdk deploy ${CONNECTOR_NAME}CdkStack;
'

echo 'FINISHED DEPLOYING INFRA FOR ${CONNECTOR_NAME}.'


# now we run the glue jobs that the CDK stack created
# If there is any output to glue_job_synchronous_execution.py, we will exit this script with a failure code.
# The 2>&1 lets us pipe both stdout and stderr to grep, as opposed to just the stdout. https://stackoverflow.com/questions/818255/what-does-21-mean
aws glue list-jobs --max-results 100 \
| jq ".JobNames[] | select(startswith(\"${CONNECTOR_NAME}gluejob\"))" \
| xargs -I{} python3 scripts/glue_job_synchronous_execution.py {} 2>&1 \
| grep -q '.' && exit 1

echo 'FINISHED RUNNING GLUE JOBS FOR ${CONNECTOR_NAME}.'

# if we are here, it means the above succeeded and we can continue by running our validation tests.
python3 scripts/exec_release_test_queries.py $CONNECTOR_NAME

echo 'FINISHED RUNNING TESTS FOR ${CONNECTOR_NAME}.'

# once that is done, we can delete our CDK stack.
# IMAGE=federation-cdk-dev ~/docker_images/gh_env.sh '\
#   source env_vars.sh;
#   cd $(dirname $(find . -name ATHENA_INFRA_SPINUP_ROOT))/app;
#   # cannot use --force because npm is stripping the flags, so pipe yes through
#   yes | npm run cdk destroy ${CONNECTOR_NAME}CdkStack;
# '

echo 'FINISHED CLEANING UP RESOURCES FOR ${CONNECTOR_NAME}.'


echo 'FINISHED RELEASE TESTS FOR ${CONNECTOR_NAME}.'
