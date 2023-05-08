## CDK Infra Spinup Readme

1. Build Docker image for all CDK work
```
export CDK_SRC_ROOT=$(dirname $(find . -name ATHENA_INFRA_SPINUP_ROOT))
cd $CDK_SRC_ROOT

sh docker_image/build.sh
```

2. Run all npm commands to synth stacks
```
IMAGE=federation-cdk-dev ~/docker_images/env.sh '\
  export CDK_SRC_ROOT=$(dirname $(find . -name ATHENA_INFRA_SPINUP_ROOT));
  cd $CDK_SRC_ROOT/app;
  npm install;
  npm run build;
  npm run cdk synth;
  npm run cdk ls
'
```

Now the development environment is properly set up for creating stacks.
