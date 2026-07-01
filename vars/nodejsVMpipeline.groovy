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
        Region = 'us-east-1'
        ACC_ID = '506572015597'
        PROJECT = 'roboshop'
        COMPONENT = 'catalogue'
    }
    parameters {
        booleanParam(name: 'deploy', defaultValue: false, description: 'Toggle this value')
    }

    stages {
        stage ('App Version') {
            steps {
                dir ('catalogue-v3') {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        def name = packageJson.name
                        echo "Building ${name} version ${appVersion}"  
                    }
                }
            }
        }

        stage ('Install Dependencies') {
            steps {
                dir ('catalogue-v3') {
                    script {
                        sh """
                            sudo dnf module disable nodejs -y
                            sudo dnf module enable nodejs:20 -y
                            sudo dnf install nodejs -y
                            npm install
                        """
                    }
                }
            }
        }

        stage ('Unit Testing') {
            steps {
                script {
                    sh """
                        echo "unit tests"
                    """
                }
            }
        }

        // stage('sonar scan') {
        //     environment {
        //         scannerHome = tool 'sonar-7.2'
        //     }
        //     steps {
        //          dir('catalogue-v3') {
        //         script {
        //             // sonar server environment
        //             withSonarQubeEnv('sonar-7.2') { 
        //                 sh "${scannerHome}/bin/sonar-scanner"
        //             }
        //         }
        //       }
        //     }
        // }
        // Enable webhook sonar qube server and wait for the result
        //  stage('Quality Gate') {
        //     steps {
        //         timeout(time: 5, unit: 'MINUTES') {
        //             // Pauses pipeline and waits for the webhook response from SonarQube
        //             waitForQualityGate abortPipeline: true 
        //         }
        //     }
        // }

//         stage('Check Dependabot Alerts') {
//     steps {
//         withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
//             script {

//                 def response = sh(
//                     script: '''
//                     curl -s -L \
//                       -H "Accept: application/vnd.github+json" \
//                       -H "Authorization: Bearer $GITHUB_TOKEN" \
//                       https://api.github.com/repos/ShivanathBabu/catalogue-Jenkins/dependabot/alerts
//                     ''',
//                     returnStdout: true
//                 ).trim()

//                 def alerts = readJSON text: response

//                 def vulnerableAlerts = alerts.findAll { alert ->
//                     def severity = alert.security_advisory.severity?.toLowerCase()
//                     def state = alert.state?.toLowerCase()

//                     state == 'open' &&
//                     ['critical', 'high'].contains(severity)
//                 }

//                 if (vulnerableAlerts.size() > 0) {

//                     echo "===== HIGH/CRITICAL DEPENDABOT ALERTS ====="

//                     vulnerableAlerts.each { alert ->
//                         echo """
// Package  : ${alert.dependency.package.name}
// Severity : ${alert.security_advisory.severity}
// CVE      : ${alert.security_advisory.cve_id}
// Summary  : ${alert.security_advisory.summary}
// """
//                     }

//                     error("Security Gate Failed. Found ${vulnerableAlerts.size()} HIGH/CRITICAL vulnerabilities.")
//                 }

//                 echo "No HIGH/CRITICAL vulnerabilities found."
//             }
//         }
//     }
// }
        stage ('Docker Build') {
            steps {
                script {
                    withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                    sh """
                        export DOCKER_BUILDKIT=0

                        aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
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

           stage ('Trigger Deploy') {
            when {
                expression {params.deploy}
            }
            steps {
                script {
                    build job: 'catalogue-cd',
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