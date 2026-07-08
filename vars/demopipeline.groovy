def call(Map configMap) {
    pipeline {
    agent {
        label 'AGENT-1'
    }

    environment {
        greeting = configMap.get('greeting')
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {

        stage('Build') {
            steps {
                script {
                    sh """
                        echo "Hello Build"
                        sleep 10
                        env
                    """
                }
            }
        }

        stage('Test') {
            steps {
                script {
                    echo 'Testing..'
                }
            }
        }

        stage('Deploy') {
            input {
                message "Should we continue?"
                ok "Yes, we should."

                parameters {
                    string(
                        name: 'PERSON',
                        defaultValue: 'Mr Jenkins',
                        description: 'Who should I say hello to?'
                    )
                }
            }

            steps {
                script {
                    echo "Hello, ${params.PERSON}, nice to meet you."
                    echo 'Deploy..'
                }
            }
        }
    }

    post {
        always {
            echo 'I will always say Hello again!'
            deleteDir()
        }

        success {
            echo 'Hello Success'
        }

        failure {
            echo 'Hello Failure'
        }
    }
}
}