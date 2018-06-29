import com.amazonaws.services.ec2.model.InstanceType
import hudson.model.*
import hudson.plugins.ec2.AmazonEC2Cloud
import hudson.plugins.ec2.EC2Tag
import hudson.plugins.ec2.SlaveTemplate
import hudson.plugins.ec2.SpotConfiguration
import hudson.plugins.ec2.UnixData
import java.util.logging.Logger
import jenkins.model.Jenkins

def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

netMap = [:]
netMap['us-west-2a'] = 'subnet-017ed13966a157f40'
netMap['us-west-2b'] = 'subnet-0706d7d3b7bf7a2a5'
netMap['us-west-2c'] = 'subnet-0b51237c5d9b9c3ed'

imageMap = [:]
imageMap['micro-amazon'] = 'ami-0ad99772'
imageMap['min-artful-x64'] = 'ami-96dd93ee'
imageMap['min-centos-6-x64'] = 'ami-6fcc8f17'
imageMap['min-centos-7-x64'] = 'ami-3ecc8f46'
imageMap['min-jessie-x64'] = 'ami-fde96b9d'
imageMap['min-stretch-x64'] = 'ami-b6e499ce'
imageMap['min-trusty-x64'] = 'ami-08fbb070'
imageMap['min-xenial-x64'] = 'ami-ba602bc2'

priceMap = [:]
priceMap['t2.small'] = '0.01'
priceMap['c4.xlarge'] = '0.10'
priceMap['m4.xlarge'] = '0.10'
priceMap['m4.2xlarge'] = '0.20'

userMap = [:]
userMap['docker'] = 'ec2-user'
userMap['docker-32gb'] = userMap['docker']
userMap['micro-amazon'] = userMap['docker']
userMap['min-artful-x64'] = 'ubuntu'
userMap['min-centos-6-x64'] = 'centos'
userMap['min-centos-7-x64'] = 'centos'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-jessie-x64'] = 'admin'
userMap['min-stretch-x64'] = 'admin'
userMap['min-trusty-x64'] = 'ubuntu'
userMap['min-xenial-x64'] = 'ubuntu'

initMap = [:]
initMap['docker'] = '''
    set -o xtrace

    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
        sudo mkfs.ext4 ${DEVICE}
        sudo mount -o noatime ${DEVICE} /mnt
    fi
    sudo ethtool -K eth0 sg off
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-1.8.0-openjdk git aws-cli docker
    sudo yum -y remove java-1.7.0-openjdk
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

    sudo sysctl net.ipv4.tcp_fin_timeout=15
    sudo sysctl net.ipv4.tcp_tw_reuse=1
    sudo sysctl net.ipv6.conf.all.disable_ipv6=1
    sudo sysctl net.ipv6.conf.default.disable_ipv6=1
    sudo sysctl -w fs.inotify.max_user_watches=10000000 || true
    sudo sysctl -w fs.aio-max-nr=1048576 || true
    sudo sysctl -w fs.file-max=6815744 || true
    echo "*  soft  core  unlimited" | sudo tee -a /etc/security/limits.conf
    sudo sed -i.bak -e 's/nofile=1024:4096/nofile=900000:900000/; s/DAEMON_MAXFILES=.*/DAEMON_MAXFILES=990000/' /etc/sysconfig/docker
    echo 'DOCKER_STORAGE_OPTIONS="--data-root=/mnt/docker"' | sudo tee -a /etc/sysconfig/docker-storage
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /usr/lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    echo sudo service docker status || sudo service docker start
    echo "*/15 * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['docker-32gb'] = initMap['docker']
initMap['micro-amazon'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-1.8.0-openjdk git aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-artful-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install openjdk-8-jre-headless git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-centos-6-x64'] = initMap['micro-amazon']
initMap['min-centos-7-x64'] = initMap['micro-amazon']
initMap['fips-centos-7-x64'] = initMap['micro-amazon']
initMap['min-jessie-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install git wget
    wget https://jenkins.percona.com/downloads/jre/jre-8u152-linux-x64.tar.gz
    sudo tar -zxf jre-8u152-linux-x64.tar.gz -C /usr/local
    sudo ln -s /usr/local/jre1.8.0_152 /usr/local/java
    sudo ln -s /usr/local/jre1.8.0_152/bin/java /usr/bin/java
    rm -fv jre-8u152-linux-x64.tar.gz
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-stretch-x64'] = initMap['min-artful-x64']
initMap['min-trusty-x64'] = initMap['min-jessie-x64']
initMap['min-xenial-x64'] = initMap['min-artful-x64']

capMap = [:]
capMap['c4.xlarge'] = '60'
capMap['m4.xlarge'] = '60'
capMap['m4.2xlarge'] = '10'

typeMap = [:]
typeMap['micro-amazon'] = 't2.small'
typeMap['docker'] = 'c4.xlarge'
typeMap['docker-32gb'] = 'm4.2xlarge'
typeMap['min-centos-7-x64'] = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-artful-x64'] = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x64'] = 'm4.xlarge'
typeMap['min-jessie-x64'] = typeMap['min-centos-6-x64']
typeMap['min-stretch-x64'] = typeMap['min-centos-7-x64']
typeMap['min-trusty-x64'] = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64'] = typeMap['min-centos-7-x64']

execMap = [:]
execMap['docker'] = '1'
execMap['docker-32gb'] = execMap['docker']
execMap['micro-amazon'] = '30'
execMap['min-artful-x64'] = '1'
execMap['min-centos-6-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-jessie-x64'] = '1'
execMap['min-stretch-x64'] = '1'
execMap['min-trusty-x64'] = '1'
execMap['min-xenial-x64'] = '1'

devMap = [:]
devMap['docker'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb'] = devMap['docker']
devMap['micro-amazon'] = devMap['docker']
devMap['min-artful-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-centos-6-x64'] = devMap['min-artful-x64']
devMap['min-centos-7-x64'] = devMap['min-artful-x64']
devMap['fips-centos-7-x64'] = devMap['min-artful-x64']
devMap['min-jessie-x64'] = devMap['micro-amazon']
devMap['min-stretch-x64'] = 'xvda=:8:true:gp2,xvdd=:80:true:gp2'
devMap['min-trusty-x64'] = devMap['min-artful-x64']
devMap['min-xenial-x64'] = devMap['min-artful-x64']

labelMap = [:]
labelMap['docker'] = ''
labelMap['docker-32gb'] = ''
labelMap['micro-amazon'] = 'master'
labelMap['min-artful-x64'] = ''
labelMap['min-centos-6-x64'] = ''
labelMap['min-centos-7-x64'] = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-jessie-x64'] = ''
labelMap['min-stretch-x64'] = ''
labelMap['min-trusty-x64'] = ''
labelMap['min-xenial-x64'] = ''

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[OSType],                           // String ami
        '',                                         // String zone
        new SpotConfiguration(priceMap[typeMap[OSType]]), // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(typeMap[OSType]),    // InstanceType type
        ( typeMap[OSType].startsWith("c") || typeMap[OSType].startsWith("m") ), // boolean ebsOptimized
        OSType + ' ' + labelMap[OSType],            // String labelString
        Node.Mode.NORMAL,                           // Node.Mode mode
        OSType,                                     // String description
        initMap[OSType],                            // String initScript
        '',                                         // String tmpDir
        '',                                         // String userData
        execMap[OSType],                            // String numExecutors
        userMap[OSType],                            // String remoteAdmin
        new UnixData('', '', '22'),                 // AMITypeData amiType
        '-Xmx512m -Xms512m',                        // String jvmopts
        false,                                      // boolean stopOnTerminate
        netMap[AZ],                                 // String subnetId
        [
            new EC2Tag('Name', 'jenkins-psmdb-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-psmdb-slave')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        false,                                      // boolean usePrivateDnsName
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-psmdb-slave', // String iamInstanceProfile
        true,                                       // boolean deleteRootOnTermination
        false,                                      // boolean useEphemeralDevices
        false,                                      // boolean useDedicatedTenancy
        '',                                         // String launchTimeoutStr
        true,                                       // boolean associatePublicIp
        devMap[OSType],                             // String customDeviceMapping
        true,                                       // boolean connectBySSHProcess
        false                                       // boolean connectUsingPublicIp
    )
}

String privateKey = ''
jenkins.clouds.each {
    if (it.hasProperty('cloudName') && it['cloudName'] == 'AWS-Dev b') {
        privateKey = it['privateKey']
    }
}

String region = 'us-west-2'
('b'..'b').each {
    // https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
    AmazonEC2Cloud ec2Cloud = new AmazonEC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        '240',                                   // String instanceCapStr
        [
            getTemplate('micro-amazon', "${region}${it}"),
            getTemplate('min-artful-x64', "${region}${it}"),
            getTemplate('min-jessie-x64', "${region}${it}"),
            getTemplate('min-stretch-x64', "${region}${it}"),
            getTemplate('min-trusty-x64', "${region}${it}"),
            getTemplate('min-xenial-x64', "${region}${it}"),
            getTemplate('min-centos-6-x64', "${region}${it}"),
            getTemplate('min-centos-7-x64', "${region}${it}"),
        ]                                       // List<? extends SlaveTemplate> templates
    )

    // add cloud configuration to Jenkins
    jenkins.clouds.each {
        if (it.hasProperty('cloudName') && it['cloudName'] == ec2Cloud['cloudName']) {
            jenkins.clouds.remove(it)
        }
    }
    jenkins.clouds.add(ec2Cloud)
}

// save current Jenkins state to disk
jenkins.save()

logger.info("Cloud init finished")
