pipeline {
   agent any
   stages {

      stage('Init') {
         steps {
            sh "rm -rf build/libs/"
            sh "chmod +x gradlew"
         }
      }

      stage ('Build') {
         steps {
            sh "./gradlew :build --refresh-dependencies --stacktrace"

            archiveArtifacts artifacts: '**/build/libs/*.jar', fingerprint: true
         }
      }

      stage('Publish') {
         when {
            branch 'master'
         }
         steps {
            sh "./gradlew :publish --stacktrace"
         }
      }
   }
}
