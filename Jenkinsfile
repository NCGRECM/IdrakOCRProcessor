pipeline {
    agent any

    options {
        // Keep the 10 most recent builds
        buildDiscarder(logRotator(numToKeepStr:'10')) 
    }

    stages {
        stage("Clean Up") {
            steps {
                deleteDir()
            }
        }

        stage ("Clone Repo") {
            steps {
                checkout scm
            }            
        }

        stage ("Build") {
            steps{
                sh "mvn clean install"
            }

            post {
                success {
                    // Archive the built artifacts
                    archive includes: 'target/ocr.processor-1-jar-with-dependencies.jar'

                    // publish html
                    publishHTML target: [
                        allowMissing: false,
                        alwaysLinkToLastBuild: false,
                        keepAll: true,
                        reportDir: 'reports',
                        reportFiles: 'index.html',
                        reportName: 'Build Report'
                    ]
                }
            }
        }
    }
}
