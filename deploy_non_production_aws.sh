#!/bin/zsh
scp target/easytax-plugin-0.1.1-SNAPSHOT.jar ubuntu@ec2-13-211-138-222.ap-southeast-2.compute.amazonaws.com:/tmp
ssh ubuntu@ec2-13-211-138-222.ap-southeast-2.compute.amazonaws.com "sudo -su tomcat /opt/kpm-latest/kpm install_java_plugin 'swoop:easytax-plugin' --from-source-file=/tmp/easytax-plugin-0.1.1-SNAPSHOT.jar --destination=/var/lib/killbill/bundles --version=0.1.1-SNAPSHOT"
ssh ubuntu@ec2-13-211-138-222.ap-southeast-2.compute.amazonaws.com "sudo service killbill restart"
