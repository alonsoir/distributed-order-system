#!/bin/zsh

mvn clean verify sonar:sonar \
  -Dsonar.projectKey=distributed-order-system \
  -Dsonar.projectName='distributed-order-system' \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=sqa_2a47909e8a15b9975015ac30fa0adb2f79e68b60 \
  -DskipTests