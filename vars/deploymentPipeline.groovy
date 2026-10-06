def call(Map config = [:]) {

    /*
     * Pipeline configuration
     */
    def repoUrl = config.get('repoUrl', '')
    def branch = config.get('branch', 'main')
    def playbook = config.get('playbook', 'gitrunans.yml')

    /*
     * Runtime configuration
     */
    def targetIp = config.get('targetIp', '')
    def sshCredentialId = config.get('sshCredentialId', '')
    def runnerTokenCredentialId = config.get('runnerTokenCredentialId', '')

    /*
     * Default values
     */
    def slackChannel = 'build-status'
    def environment = 'prod'
    def codeBasePath = 'env/prod'
    def actionMessage = 'Ansible deployment completed successfully.'
    def keepApprovalStage = true

    /*
     * ---------------------------------------------------------
     * Clone
     * ---------------------------------------------------------
     */
    stage('Clone') {

        echo "Cloning Ansible Assignment 5 repository..."
        echo "Repository: ${repoUrl}"
        echo "Branch: ${branch}"

        if (!repoUrl) {
            error "Ansible repository URL is required."
        }

        git branch: branch, url: repoUrl
    }

    /*
     * ---------------------------------------------------------
     * Configuration
     * ---------------------------------------------------------
     */
    stage('Configuration') {

        echo "Loading Shared Library configuration..."

        /*
         * Load config.properties from the Shared Library
         * resources directory.
         */
        def configText = libraryResource('config.properties')

        def configFile = 'shared-library-config.properties'

        writeFile(
            file: configFile,
            text: configText
        )

        def properties = readProperties file: configFile

        slackChannel = properties.get(
            'SLACK_CHANNEL_NAME',
            slackChannel
        )

        environment = properties.get(
            'ENVIRONMENT',
            environment
        )

        codeBasePath = properties.get(
            'CODE_BASE_PATH',
            codeBasePath
        )

        actionMessage = properties.get(
            'ACTION_MESSAGE',
            actionMessage
        )

        def approvalValue = properties.get(
            'KEEP_APPROVAL_STAGE',
            keepApprovalStage.toString()
        )

        keepApprovalStage = approvalValue.toString().toBoolean()

        echo "Environment: ${environment}"
        echo "Code Base Path: ${codeBasePath}"
        echo "Slack Channel: ${slackChannel}"
        echo "Keep Approval Stage: ${keepApprovalStage}"
    }

    /*
     * ---------------------------------------------------------
     * Validate Inputs
     * ---------------------------------------------------------
     */
    stage('Validate Inputs') {

        if (!targetIp) {
            error "TARGET_IP is required."
        }

        if (!sshCredentialId) {
            error "SSH credential ID is required."
        }

        if (!runnerTokenCredentialId) {
            error "GitHub runner token credential ID is required."
        }

        echo "Target EC2: ${targetIp}"
        echo "Playbook: ${playbook}"
        echo "SSH credential configured."
        echo "GitHub runner token credential configured."
    }

    /*
     * ---------------------------------------------------------
     * User Approval
     * ---------------------------------------------------------
     */
    stage('User Approval') {

        if (keepApprovalStage) {

            input(
                message: """Approve deployment to ${environment}?

Target EC2: ${targetIp}
Playbook: ${playbook}""",
                ok: 'Approve'
            )

        } else {

            echo "Approval stage disabled by configuration."
        }
    }

    /*
     * ---------------------------------------------------------
     * Playbook Execution
     * ---------------------------------------------------------
     */
    stage('Playbook Execution') {

        echo "Preparing Ansible execution..."
        echo "Target EC2: ${targetIp}"
        echo "Environment: ${environment}"
        echo "Code Base Path: ${codeBasePath}"

        /*
         * Generate a temporary inventory using
         * the EC2 IP supplied at build time.
         */
        writeFile(
            file: 'inventory.dynamic',
            text: """[github_runner]
github-runner ansible_host=${targetIp} ansible_user=ubuntu
"""
        )

        /*
         * Jenkins SSH credential authenticates to EC2.
         * Jenkins Secret Text credential supplies the
         * GitHub Actions runner registration token.
         */
        sshagent(credentials: [sshCredentialId]) {

            withCredentials([
                string(
                    credentialsId: runnerTokenCredentialId,
                    variable: 'GITHUB_RUNNER_TOKEN'
                )
            ]) {

                withEnv([
                    "ANSIBLE_HOST_KEY_CHECKING=False",
                    "ANSIBLE_ENVIRONMENT=${environment}",
                    "ANSIBLE_CODE_BASE_PATH=${codeBasePath}"
                ]) {

                    sh '''
                        set -e

                        echo "Testing Ansible connectivity..."

                        ansible all \
                          -i inventory.dynamic \
                          -m ping

                        echo "Executing GitHub Actions runner playbook..."

                        ansible-playbook \
                          gitrunans.yml \
                          -i inventory.dynamic \
                          -e "environment=${ANSIBLE_ENVIRONMENT}" \
                          -e "code_base_path=${ANSIBLE_CODE_BASE_PATH}" \
                          -e "github_runner_token=${GITHUB_RUNNER_TOKEN}"
                    '''
                }
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * Notification
     * ---------------------------------------------------------
     */
    stage('Notification') {

        echo "${actionMessage}"

        slackSend(
            channel: slackChannel,
            message: "${actionMessage} Environment: ${environment}"
        )
    }
}
