// PayLedger CI (ADR 0022). Runs in the Jenkins container from jenkins/: JDK 21, Node, Docker CLI, Chromium.
pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    stages {
        stage('Prepare') {
            steps {
                // throwaway .env with random values: no secret is stored in Jenkins or in the repository
                sh 'bash ops/ci/make-env.sh'
            }
        }

        stage('Build and test') {
            parallel {
                stage('Backend') {
                    steps {
                        sh './mvnw -B -ntp verify'
                    }
                    post {
                        always {
                            junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: false
                        }
                    }
                }
                stage('Admin UI') {
                    steps {
                        dir('admin-ui') {
                            sh 'npm ci && npm run build'
                        }
                    }
                }
            }
        }

        stage('Stack smoke') {
            steps {
                sh 'bash ops/ui/smoke.sh'
            }
            post {
                always {
                    archiveArtifacts artifacts: 'docs/img/admin-ui.png', allowEmptyArchive: true
                }
            }
        }
    }

    post {
        always {
            sh 'docker compose -p payledger-ui down -v || true'
            cleanWs(deleteDirs: true, notFailBuild: true)
        }
    }
}
