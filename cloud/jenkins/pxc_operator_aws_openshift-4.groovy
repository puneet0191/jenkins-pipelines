void pushArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}

TestsReport = '<testsuite  name=\\"PXC\\">\n'
testsReportMap = [:]
void makeReport() {
    for ( test in testsReportMap ) {
        TestsReport = TestsReport + "<testcase name=\\\"${test.key}\\\"><${test.value}/></testcase>\n"
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void runTest(String TEST_NAME) {
    try {
        echo "The $TEST_NAME test was started!"

        GIT_SHORT_COMMIT = sh(script: 'git -C source describe --always --dirty', , returnStdout: true).trim()
        VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
        testsReportMap[TEST_NAME] = 'failure'

        popArtifactFile("$VERSION-$TEST_NAME")

        sh """
            if [ -f "$VERSION-$TEST_NAME" ]; then
                echo Skip $TEST_NAME test
            else
                cd ./source
                if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                    export IMAGE=${PXC_OPERATOR_IMAGE}
                else
                    export IMAGE=perconalab/percona-xtradb-cluster-operator:${env.GIT_BRANCH}
                fi

                if [ -n "${IMAGE_PXC}" ]; then
                    export IMAGE_PXC=${IMAGE_PXC}
                fi

                if [ -n "${IMAGE_PROXY}" ]; then
                    export IMAGE_PROXY=${IMAGE_PROXY}
                fi

                if [ -n "${IMAGE_BACKUP}" ]; then
                    export IMAGE_BACKUP=${IMAGE_BACKUP}
                fi

                if [ -n "${IMAGE_PMM}" ]; then
                    export IMAGE_PMM=${IMAGE_PMM}
                fi

                source $HOME/google-cloud-sdk/path.bash.inc
                export KUBECONFIG=$WORKSPACE/openshift/auth/kubeconfig
                oc whoami

                ./e2e-tests/$TEST_NAME/run
                touch $VERSION-$TEST_NAME
            fi
        """
        pushArtifactFile("$VERSION-$TEST_NAME")
        testsReportMap[TEST_NAME] = 'passed'
    }
    catch (exc) {
        currentBuild.result = 'FAILURE'
    }

    echo "The $TEST_NAME test was finished!"
}
void installRpms() {
    sh """
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 jq | true
    """
}
pipeline {
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:master',
            name: 'PXC_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'PXC image: perconalab/percona-xtradb-cluster-operator:master-pxc',
            name: 'IMAGE_PXC')
        string(
            defaultValue: '',
            description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:master-proxysql',
            name: 'IMAGE_PROXY')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-xtradb-cluster-operator:master-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/percona-server-mongodb-operator:master-pmm',
            name: 'IMAGE_PMM')
    }
    environment {
        TF_IN_AUTOMATION = 'true'
    }
    agent {
         label 'docker' 
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '10', artifactNumToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                sh """
                    wget https://releases.hashicorp.com/terraform/0.11.14/terraform_0.11.14_linux_amd64.zip
                    unzip terraform_0.11.14_linux_amd64.zip
                    sudo mv terraform /usr/local/bin/ && rm terraform_0.11.14_linux_amd64.zip
                """
                installRpms()
                sh '''
                    if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                        rm -rf $HOME/google-cloud-sdk
                        curl https://sdk.cloud.google.com | bash
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud components update kubectl
                    gcloud version

                    curl -s https://storage.googleapis.com/kubernetes-helm/helm-v2.14.0-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -

                    VERSION=$(curl --silent 'https://mirror.openshift.com/pub/openshift-v4/clients/ocp/latest/release.txt' | grep 'Version:' | awk '{print $2}')
                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/latest/openshift-client-linux-$VERSION.tar.gz \
                        | sudo tar -C /usr/local/bin --wildcards -zxvpf -
                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/latest/openshift-install-linux-$VERSION.tar.gz \
                        | sudo tar -C /usr/local/bin  --wildcards -zxvpf -
                '''

            }
        }
        stage('Build docker image') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf source
                        ./cloud/local/checkout $GIT_REPO $GIT_BRANCH

                        if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PXC operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                                ./e2e-tests/build
                                docker logout
                            "
                            sudo rm -rf ./build
                        fi
                    '''
                }
            }
        }
        stage('Create AWS Infrastructure') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT_CONF_FILE')]) {
                     sh """
                         mkdir openshift
                         cp $OPENSHIFT_CONF_FILE ./openshift/install-config.yaml
                     """
                     sshagent(['aws-openshift-41-key']) {
                         sh """
                             /usr/local/bin/openshift-install create cluster --dir=./openshift/
                         """
                    }
               }

            }
        }
        stage('E2E Basic Tests') {
            steps {
                runTest('init-deploy')
                runTest('limits')
                runTest('monitoring')
                runTest('affinity')
           }
        }
        stage('E2E Scaling') {
            steps {
                runTest('scaling')
                runTest('scaling-proxysql')
                runTest('upgrade')
                runTest('upgrade-consistency')
            }
        }
        stage('E2E SelfHealing') {
            steps {
                runTest('self-healing-advanced')
                runTest('one-pod')
                runTest('auto-tuning')
            }
        }
        stage('E2E Backups') {
            steps {
                runTest('recreate')
                runTest('demand-backup')
                runTest('scheduled-backup')
            }
        }
        stage('E2E BigData') {
            steps {
                runTest('big-data')
            }
        }
        stage('Make report') {
            steps {
                makeReport()
                sh """
                    echo "${TestsReport}" > TestsReport.xml
                """
                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                archiveArtifacts '*.xml'
            }
        }
    }

    post {
        always {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
                     sshagent(['aws-openshift-41-key']) {
                         sh """
                             /usr/local/bin/openshift-install destroy cluster --dir=./openshift/
                         """
                     }
                }
            
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}