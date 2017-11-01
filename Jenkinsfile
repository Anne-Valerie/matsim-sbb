#!groovy

//loading https://code.sbb.ch/projects/KD_WZU/repos/wzu-pipeline-helper
@Library('wzu-pipeline-helper') _

pipeline {
    agent { label 'java' }
    tools {
        maven 'Apache Maven 3.3'
        jdk 'OpenJDK 1.8 64-Bit'
    }
    stages {
        stage('When on develop, Deploy Snapshot and analyze for sonar') {
            when {
                branch 'develop'
            }
            steps {
                withSonarQubeEnv('Sonar SBB CFF FFS AG') {
                    sh 'mvn -B clean deploy'
                }
            }
        }
        stage('Unit Tests') {
            steps {
                sh 'mvn -B test'
                junit '**/target/surefire-reports/*.xml'
            }
        }
        stage('When on master, Release: Adapt poms, tag, deploy and push.') {
            when {
                branch 'master'
            }
            steps {
                releaseMvn()
            }
        }
    }
}