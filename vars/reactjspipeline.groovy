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
            PROJECT = 'Roboshop'
            COMPONENT = 'USER'
            REGION = 'us-east-1'
        }

         parameters {
        booleanParam(name: 'deploy', defaultValue: false, description: 'Toggle this value')
        }
        stage {

            stages("App version"){
                steps {
                    script {
                       def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        def name = packageJson.name
                        echo "Building ${name} version ${appVersion}"  
                    }
                }
            }

            stages("Install Dependencies"){
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

            stages("unti Testing"){
                steps{
                    script{
                        echo "unit tests"
                    }
                }
            }

            stages("Docker Build"){
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
            withAWS(credentials: 'aws-creds', region: REGION) {

                // Get Image Digest
                def imageDigest = sh(
                    script: """
                        aws ecr describe-images \
                          --repository-name ${PROJECT}/${COMPONENT} \
                          --image-ids imageTag=${appVersion} \
                          --region ${REGION} \
                          --query 'imageDetails[0].imageDigest' \
                          --output text
                    """,
                    returnStdout: true
                ).trim()

                echo "Image Digest: ${imageDigest}"

                // Wait for Scan Completion
                timeout(time: 5, unit: 'MINUTES') {
                    waitUntil {

                        sleep(time: 10, unit: 'SECONDS')

                        def status = sh(
                            script: """
                                aws ecr describe-image-scan-findings \
                                  --repository-name ${PROJECT}/${COMPONENT} \
                                  --image-id imageDigest=${imageDigest} \
                                  --region ${REGION} \
                                  --query 'imageScanStatus.status' \
                                  --output text 2>/dev/null || echo IN_PROGRESS
                            """,
                            returnStdout: true
                        ).trim()

                        echo "Current Scan Status: ${status}"

                        return status == "COMPLETE"
                    }
                }

                // Get Scan Findings
                def findings = sh(
                    script: """
                        aws ecr describe-image-scan-findings \
                          --repository-name ${PROJECT}/${COMPONENT} \
                          --image-id imageDigest=${imageDigest} \
                          --region ${REGION} \
                          --output json
                    """,
                    returnStdout: true
                ).trim()

                def json = readJSON text: findings

                // Handle null findings safely
                def findingsList = json.imageScanFindings.findings ?: []

                def highCritical = findingsList.findAll {
                    it.severity in ['HIGH', 'CRITICAL']
                }

                echo "Total Vulnerabilities Found: ${findingsList.size()}"
                echo "HIGH/CRITICAL Vulnerabilities: ${highCritical.size()}"

                if (highCritical.size() > 0) {

                    echo "===== HIGH / CRITICAL VULNERABILITIES ====="

                    highCritical.each {
                        echo """
Name       : ${it.name}
Severity   : ${it.severity}
Package    : ${it.attributes?.find { a -> a.key == 'package_name' }?.value ?: 'N/A'}
Description: ${it.description ?: 'N/A'}
---------------------------------------------
"""
                    }

                    error("Build Failed: Found ${highCritical.size()} HIGH/CRITICAL vulnerabilities.")

                } else {
                    echo "✅ No HIGH or CRITICAL vulnerabilities found."
                }
            }
        }
    }
}
        stage("Trigger Deploy ")
        when {
            expression {params.deploy}
        }
        steps {
            script {
                build job: '../${COMPONENT}-cd'
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