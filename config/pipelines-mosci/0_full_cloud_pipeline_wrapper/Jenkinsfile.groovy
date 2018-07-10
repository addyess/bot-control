if (params.SLAVE_NODE_NAME) {
    specific_slave=params.SLAVE_NODE_NAME
}
else if (params.PERSIST_SLAVE) {
    specific_slave="${params.SLAVE_LABEL}-${ARCH}-persist"
} 
else {
    specific_slave="${params.SLAVE_LABEL}-${ARCH}"
}

workSpace="full_pipeline-${env.BUILD_ID}"

echo "Slave selection logic has selected ${specific_slave}"

// We need a random wait here, because this next bit can be racy:
// Two jobs can run on this node, while one changes the label to 'locked'
// The second is already running there while the label is changed - and attempts to change locked to ... locked

// echo "Sleeping for a random amount of time, between 0 and 60 seconds..."
// sleep((long)(Math.random() * 120));

// Make sure arch is s390x if cloud is s390x and vice versa

echo "PHASES: ${PHASES}"

s390xcheck = ["${params.CLOUD_NAME}", "${params.ARCH}"]
if ( s390xcheck.any { it.contains("390") } ) {
        if ( ! s390xcheck.every { it.contains("390") } ) {
                echo "${ARCH} and ${CLOUD_NAME} is not a supported combination"
                currentBuild.result = 'FAILURE'
                return
        }
}

// Modify BOOTSTRAP_CONSTRAINTS and MODEL_CONSTRAINTS if s390x

if ( params.CLOUD_NAME.contains("390") ) {
        BOOTSTRAP_CONSTRAINTS=''
        MODEL_CONSTRAINTS="arch=s390x"
}

/* Throttle the job here.

        This job needs to check if other jobs of ARCH are running, and if so, wait until they aren't.
        Jenkins should handle this but the Throttle Builds plugin apparently doesn't work with pipelines
        properly.

        So, we can get all builds, and check if there is already a build with ARCH in it. If yes, wait. If no, build.

*/        

waitUntil {
node (specific_slave) { 
        echo "Picking ${NODE_NAME} for this job run"
        echo "OPENSTACK_PUBLIC_IP = ${OPENSTACK_PUBLIC_IP}"
        for ( node in jenkins.model.Jenkins.instance.nodes ) {
                if (node.getNodeName().equals(NODE_NAME)) {
                        OLD_LABEL=node.getLabelString()
                        if ( OLD_LABEL == "locked" ) {
                                return false
                        }
                        NEW_LABEL="locked"
                        SLAVE_NODE_NAME=node.getNodeName()
                        node.setLabelString(NEW_LABEL)
                        node.save()
                        echo "Changing node label from ${OLD_LABEL} to ${NEW_LABEL}"
                }
        } 
    } 
return true
}
node(SLAVE_NODE_NAME) {
        ws(workSpace) {
        environment {
            MODEL_CONSTRAINTS="${MODEL_CONSTRAINTS}"
            BOOTSTRAP_CONSTRAINTS="${BOOTSTRAP_CONSTRAINTS}"
            SLAVE_NODE_NAME="${env.NODE_NAME}"
        }
        stage("Preparation: ${params.ARCH}") {
            // Logic for differentiating between MAAS, s390x, or something else (probably oolxd)
            echo "Cloud name set to ${CLOUD_NAME}"
            SLAVE_NODE_NAME="${env.NODE_NAME}"
            if ( PHASES.contains("Preparation") ) {
            prep_job = build job: '1. Full Cloud - Prepare', parameters: [[$class: 'StringParameterValue', name: 'CTI_GIT_REPO', value: "${params.CTI_GIT_REPO}"],
                       [$class: 'StringParameterValue', name: 'CTI_GIT_BRANCH', value: "${params.CTI_GIT_BRANCH}"],
                       [$class: 'StringParameterValue', name: 'SLAVE_NODE_NAME', value: "${SLAVE_NODE_NAME}"],
                       [$class: 'StringParameterValue', name: 'WORKSPACE', value: workSpace],
                       [$class: 'StringParameterValue', name: 'ARCH', value: params.ARCH],
                       [$class: 'StringParameterValue', name: 'CLOUD_NAME', value: params.CLOUD_NAME],
                       [$class: 'StringParameterValue', name: 'MODEL_CONSTRAINTS', value: "${params.MODEL_CONSTRAINTS}"],
                       [$class: 'StringParameterValue', name: 'BOOTSTRAP_CONSTRAINTS', value: "${params.BOOTSTRAP_CONSTRAINTS}"]]
            // Enable / disable verbose logging
            /*if ("${VERBOSE_LOGS}") {
                for(String line : prep_job.getRawBuild().getLog(100)){
                        echo line
                }
            }*/
            }
        }
        stage("Bootstrap: ${params.ARCH}") {
        // Bootstrap the environment from ${CLOUD_NAME}
            echo "Bootstrapping $ARCH from ${CLOUD_NAME}"
            SLAVE_NODE_NAME="${env.NODE_NAME}"
            if ( PHASES.contains("Bootstrap") ) {
            bootstrap_job = build job: '2. Full Cloud - Bootstrap', parameters: [[$class: 'StringParameterValue', name: 'CLOUD_NAME', value: params.CLOUD_NAME],
                            [$class: 'BooleanParameterValue', name: 'FORCE_RELEASE', value: Boolean.valueOf(FORCE_RELEASE)],
                            [$class: 'BooleanParameterValue', name: 'FORCE_NEW_CONTROLLER', value: Boolean.valueOf(FORCE_NEW_CONTROLLER)],
                            [$class: 'BooleanParameterValue', name: 'PRE_RELEASE_MACHINES', value: Boolean.valueOf(PRE_RELEASE_MACHINES)],
                            [$class: 'BooleanParameterValue', name: 'BOOTSTRAP_ON_SLAVE', value: Boolean.valueOf(BOOTSTRAP_ON_SLAVE)],
                            [$class: 'StringParameterValue', name: 'ARCH', value: params.ARCH],
                            [$class: 'StringParameterValue', name: 'S390X_NODES', value: params.S390X_NODES],
                            [$class: 'StringParameterValue', name: 'WORKSPACE', value: workSpace],
                            [$class: 'StringParameterValue', name: 'SLAVE_NODE_NAME', value: SLAVE_NODE_NAME],
                            [$class: 'StringParameterValue', name: 'MODEL_CONSTRAINTS', value: params.MODEL_CONSTRAINTS],
                            [$class: 'StringParameterValue', name: 'BOOTSTRAP_CONSTRAINTS', value: params.BOOTSTRAP_CONSTRAINTS],
                            [$class: 'StringParameterValue', name: 'OVERRIDE_MODEL_CONFIG', value: params.OVERRIDE_MODEL_CONFIG],
                            [$class: 'StringParameterValue', name: 'OVERRIDE_CONTROLLER_CONFIG', value: params.OVERRIDE_CONTROLLER_CONFIG]]
            /*if ("${VERBOSE_LOGS}") {
                for(String line : bootstrap_job.getRawBuild().getLog(100)){
                        echo line
                }
            }*/
            //sh 'cd examples ; ./controller-arm64.sh'
            }
        }
        stage("Deploy: ${params.ARCH}") {
            echo 'Deploy'
            SLAVE_NODE_NAME="${env.NODE_NAME}"
            if ( PHASES.contains("Deploy") ) {
            deploy_job = build job: '3. Full Cloud - Deploy', parameters: [[$class: 'StringParameterValue', name: 'CLOUD_NAME', value: CLOUD_NAME],
                         [$class: 'BooleanParameterValue', name: 'OPENSTACK', value: Boolean.valueOf(OPENSTACK)],
                         [$class: 'StringParameterValue', name: 'WORKSPACE', value: workSpace],
                         [$class: 'StringParameterValue', name: 'ARCH', value: params.ARCH],
                         [$class: 'StringParameterValue', name: 'SLAVE_NODE_NAME', value: "${SLAVE_NODE_NAME}"],
                         [$class: 'StringParameterValue', name: 'NEUTRON_DATAPORT', value: NEUTRON_DATAPORT],
                         [$class: 'StringParameterValue', name: 'BUNDLE_URL', value: "${params.BUNDLE_URL}"],
                         [$class: 'StringParameterValue', name: 'BUNDLE_PASTE', value: params.BUNDLE_PASTE],
                         [$class: 'StringParameterValue', name: 'OVERRIDE_BUNDLE_CONFIG', value: params.OVERRIDE_BUNDLE_CONFIG]]
                //sh 'ls -lart'
                //sh 'cd runners/manual-examples ; ./openstack-base-xenial-ocata-arm64-manual.sh'
            }
        }
        stage("Configure: ${params.ARCH}") {
            echo "Configure"
            SLAVE_NODE_NAME="${env.NODE_NAME}"
            if ( PHASES.contains("Configure") && Boolean.valueOf(OPENSTACK) == true ) {
            configure_job = build job: '4. Full Cloud - Configure', parameters: [[$class: 'StringParameterValue', name: 'CLOUD_NAME', value: CLOUD_NAME],
                         [$class: 'StringParameterValue', name: 'WORKSPACE', value: workSpace],
                         [$class: 'StringParameterValue', name: 'KEYSTONE_API_VERSION', value: params.KEYSTONE_API_VERSION],
                         [$class: 'StringParameterValue', name: 'SLAVE_NODE_NAME', value: SLAVE_NODE_NAME],
                         [$class: 'StringParameterValue', name: 'ARCH', value: params.ARCH]]
            }
        }
        stage("Test: ${params.ARCH}") {
            echo 'Test Cloud'
            if ( PHASES.contains("Test") ) {
            SLAVE_NODE_NAME="${env.NODE_NAME}"
            test_job = build job: '5. Full Cloud - Test', parameters: [[$class: 'StringParameterValue', name: 'CLOUD_NAME', value: CLOUD_NAME],
                         [$class: 'BooleanParameterValue', name: 'OPENSTACK', value: Boolean.valueOf(OPENSTACK)],
                         [$class: 'StringParameterValue', name: 'WORKSPACE', value: workSpace],
                         [$class: 'StringParameterValue', name: 'SELECTED_TESTS', value: params.SELECTED_TESTS],
                         [$class: 'StringParameterValue', name: 'SLAVE_NODE_NAME', value: SLAVE_NODE_NAME],
                         [$class: 'StringParameterValue', name: 'ARCH', value: params.ARCH]]
            }
        }
        stage("Teardown: ${params.ARCH}") {
            echo 'Teardown'
            SLAVE_NODE_NAME="${env.NODE_NAME}"
            if ( PHASES.contains("Teardown") ) {
            deploy_job = build job: '6. Full Cloud - Teardown', parameters: [[$class: 'StringParameterValue', name: 'CLOUD_NAME', value: "${params.CLOUD_NAME}"],
                         [$class: 'StringParameterValue', name: 'ARCH', value: "${params.ARCH}"],
                         [$class: 'StringParameterValue', name: 'WORKSPACE', value: workSpace],
                         [$class: 'StringParameterValue', name: 'SLAVE_NODE_NAME', value: "${SLAVE_NODE_NAME}"],
                         [$class: 'StringParameterValue', name: 'MODEL_CONSTRAINTS', value: params.MODEL_CONSTRAINTS],
                         [$class: 'StringParameterValue', name: 'MAAS_OWNER', value: params.MAAS_OWNER],
                         [$class: 'StringParameterValue', name: 'S390X_NODES', value: params.S390X_NODES],
                         [$class: 'BooleanParameterValue', name: 'RELEASE_MACHINES', value: Boolean.valueOf(RELEASE_MACHINES)],
                         [$class: 'BooleanParameterValue', name: 'FORCE_RELEASE', value: Boolean.valueOf(FORCE_RELEASE)],
                         [$class: 'BooleanParameterValue', name: 'OFFLINE_SLAVE', value: Boolean.valueOf(OFFLINE_SLAVE)],
                         [$class: 'BooleanParameterValue', name: 'DESTROY_SLAVE', value: Boolean.valueOf(DESTROY_SLAVE)],
                         [$class: 'BooleanParameterValue', name: 'DESTROY_CONTROLLER', value: Boolean.valueOf(DESTROY_CONTROLLER)],
                         [$class: 'BooleanParameterValue', name: 'DESTROY_MODEL', value: Boolean.valueOf(DESTROY_MODEL)],
                         [$class: 'StringParameterValue', name: 'BUNDLE_URL', value: "${params.BUNDLE_URL}"]]
            }
        }
    }
}
