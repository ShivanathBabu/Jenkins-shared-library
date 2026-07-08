def call(configMap) {
    pipeline {
        agent {
            label 'AGENT-1'
        }
        options {
        // Timeout counter starts AFTER agent is allocated
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }
        environment {
            appVersion = ''
            ACC_ID = '506572015597'
            PROJECT = configMap.get('project')
            COMPONENT = configMap.get('component')
            REGION = 'us-east-1'
        }

         parameters {
        booleanParam(name: 'deploy', defaultValue: false, description: 'Toggle this value')
        }
        stages {

            stage("App version"){
                steps {
                    script {
                       def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        def name = packageJson.name
                        echo "Building ${name} version ${appVersion}"  
                    }
                }
            }

            stage("Install Dependencies"){
                steps{
                    script{
                        sh """
                          sudo dnf module disable nodejs -y
                          sudo dnf module enable nodejs:20 -y
                          sudo dnf install nodejs -y
                          npm install  
                        """
                    }
                }
            }

            stage("unti Testing"){
                steps{
                    script{
                        echo "unit tests"
                    }
                }
            }

            stage("Docker Build"){
                steps{
                    script{
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                        sh """
                        export DOCKER_BUILDKIT=0
                        aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com
                        docker build -t ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .
                        docker push ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                        """
                    }
                  }
                }
            }

         stage('Check Scan Results') {
            steps {
                script {
                    withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                    // Fetch scan findings
                        def findings = sh(
                            script: """
                                aws ecr describe-image-scan-findings \
                                --repository-name ${PROJECT}/${COMPONENT} \
                                --image-id imageTag=${appVersion} \
                                --region ${REGION} \
                                --output json
                            """,
                            returnStdout: true
                        ).trim()

                        // Parse JSON
                        def json = readJSON text: findings

                        def highCritical = json.imageScanFindings.findings.findAll {
                            it.severity == "HIGH" || it.severity == "CRITICAL"
                        }

                        if (highCritical.size() > 0) {
                            echo "❌ Found ${highCritical.size()} HIGH/CRITICAL vulnerabilities!"
                            currentBuild.result = 'FAILURE'
                            error("Build failed due to vulnerabilities")
                        } else {
                            echo "✅ No HIGH/CRITICAL vulnerabilities found."
                        }
                    }
                }
            }
        }
        stage("Trigger Deploy ") {
        when {
            expression {params.deploy}
        }
        steps {
            script {
                build job: "../${COMPONENT}-cd",
                parameters: [
                        string(name: 'appVersion', value: '${appVersion}'),
                        string(name: 'deploy_to', value: 'dev')
                    ],
                    propagate: false,
                    wait: false
            }
        }
        }

        }
    }
}