def call(String REPO_NAME, String DESTINATION) {
    node('master') {
        unstash 'uploadPath'
        def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()

        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                sh """
                    cat /etc/hosts > ./hosts
                    echo '10.30.6.9 repo.ci.percona.com' >> ./hosts
                    sudo cp ./hosts /etc || true

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                        set -o errexit
                        set -o xtrace

                        pushd ${path_to_build}/binary
                            for rhel in `ls -1 redhat`; do
                                export rpm_dest_path=/srv/repo-copy/${REPO_NAME}/yum/${DESTINATION}/\${rhel}

                                # RPMS
                                mkdir -p \${rpm_dest_path}/RPMS
                                for arch in `ls -1 redhat/\${rhel}`; do
                                    repo_path=\${rpm_dest_path}/RPMS/\${arch}
                                    mkdir -p \${repo_path}
                                    if [ `ls redhat/\${rhel}/\${arch}/*.rpm | wc -l` -gt 0 ]; then
                                        rsync -aHv redhat/\${rhel}/\${arch}/*.rpm \${repo_path}/
                                    fi
                                    createrepo --update \${repo_path}
                                done

                                # SRPMS
                                mkdir -p \${rpm_dest_path}/SRPMS
                                if [ `find ../source/redhat -name '*.src.rpm' | wc -l` -gt 0 ]; then
                                    cp -v `find ../source/redhat -name '*.src.rpm' \${find_exclude}` \${rpm_dest_path}/SRPMS/
                                fi
                                createrepo --update \${rpm_dest_path}/SRPMS
                            done

                            if [ "x${DESTINATION}" == "xrelease" ]; then
                                DESTINATION=main
                            fi
                            for dist in `ls -1 debian`; do
                                for deb in `find debian/\${dist} -name '*.deb'`; do
                                 pkg_fname=\$(basename \${deb})
                                 EC=0
                                 /usr/local/reprepro5/bin/reprepro --list-format '"'"'\${package}_\${version}_\${architecture}.deb\\n'"'"' -Vb /srv/repo-copy/${REPO_NAME}/apt -C ${DESTINATION} list \${dist} | sed -re "s|[0-9]:||" | grep \${pkg_fname} > /dev/null || EC=\$?
                                 REPOPUSH_ARGS=""
                                 if [ \${EC} -eq 0 ]; then
                                     REPOPUSH_ARGS=" --remove-package "
                                 fi
                                 env PATH=/usr/local/reprepro5/bin:${PATH} repopush \${REPOPUSH_ARGS} --gpg-pass ${SIGN_PASSWORD} --package \${deb} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/${REPO_NAME}/apt
                                done
                            done
                        popd

                        if [ "x${DESTINATION}" == "xmain" ]; then
                            DESTINATION=release
                        fi
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${REPO_NAME}/yum/${DESTINATION}/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/${REPO_NAME}/yum/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${REPO_NAME}/apt/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/${REPO_NAME}/apt/

                        # Clean CDN cache for repo.percona.com
                        bash -xe /usr/local/bin/clear_cdn_cache.sh
                    '
                """
            }
        }
    }
}
