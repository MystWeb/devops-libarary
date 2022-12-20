// 加载共享库
@Library("mylib@main") _

// 导入库
import org.devops.GitLab
import org.devops.Kubernetes

// New实例化
def gitlab = new GitLab()
def kubernetes = new Kubernetes()
pipeline {
    agent {
        label "k8s"
    }

    options {
        skipDefaultCheckout true
        buildDiscarder logRotator(daysToKeepStr: '30', numToKeepStr: '30')
    }

    parameters {
        string defaultValue: 'RELEASE-1.1.1', description: '注意：选择发布分支', name: 'branchName'
        choice choices: ['devops.maven.service', 'devops.ui.service'], description: '服务访问的域名', name: 'domainName'
        string defaultValue: '8080', description: '注意：服务监听的端口号', name: 'port'
    }

    stages {
        stage("Global") {
            steps {
                script {
                    // 任务名称截取构建类型（任务名称示例：devops-maven-service）
//                    env.buildType = "${JOB_NAME}".split("-")[1]
                    // JOB任务前缀（业务名称/组名称）
                    env.buName = "${JOB_NAME}".split('-')[0]
                    // 服务/项目名称
                    env.serviceName = "${JOB_NAME}".split('_')[0]
                    // GitLab用户Token访问凭据Id
                    env.gitlabUserTokenCredentialsId = "926a978a-5cef-49ca-8ff8-5351ed0700bf"
                    // Git项目Id
                    env.projectId = gitlab.GetProjectId("${env.gitlabUserTokenCredentialsId}", "${env.buName}", "${env.serviceName}")
                    // Git提交ID
                    env.commitId = gitlab.GetShortCommitIdByApi("${env.gitlabUserTokenCredentialsId}", "${env.projectId}", "${env.branchName}")
                    // 服务版本号（推荐定义："${branchName}-${commitId}"）
                    env.version = "${env.branchName}-${env.commitId}"

                    // 修改Jenkins构建描述
                    currentBuild.description = """ branchName：${env.branchName} \n commitId：${env.commitId} \n namespace：${env.buName} \n """
                    // 修改Jenkins构建名称
                    currentBuild.displayName = "${env.version}"
                }
            }
        }

        stage("K8sReleaseFile") {
            steps {
                script {
                    // Git项目Id（devops-k8s-deployment）
                    k8sProjectId = gitlab.GetProjectId("${env.gitlabUserTokenCredentialsId}", "devops", "devops-k8s-deployment")
                    // 下载Kubernetes部署文件
                    env.deployFileName = "${env.version}.yaml"
                    // 文件路径：项目服务名称/版本号.yaml
                    filePath = "${env.serviceName}%2f${env.deployFileName}"
                    // 下载Kubernetes部署模板文件
                    fileData = gitlab.GetRepositoryFile("${env.gitlabUserTokenCredentialsId}", "${k8sProjectId}", "${filePath}", "main")
                    kubernetes.K8sReleaseTemplateFileReplace("${env.deployFileName}", "${fileData}", "${env.domainName}",
                            "${env.port}", "${env.serviceName}", "${env.buName}")
                }
            }
        }

        stage("K8sDeploy") {
            steps {
                script {
                    kubernetes.KubernetesDeploy("${env.buName}", "${env.deployFileName}", "${env.serviceName}")
                }
            }
        }

        /*stage("HealthCheck") {
            steps {
                script {
                    // 注意：自定义域名需配置Hosts文件！
                    result = sh returnStdout: true, script: """ curl "http://${env.domainName}/health" """ - "\n"
                    if ("ok" == result) {
                        println("Successful！")
                    }
                }
            }
        }*/

        stage("RollOut") {
            input {
                message "是否进行回滚"
                ok "提交"
                submitter "admin,myst"
                parameters {
                    choice(choices: ['no', 'yes'], name: 'opts')
                }
            }

            steps {
                script {
                    switch ("${opts}") {
                        case "yes":
                            sh "kubectl rollout undo deployment/${env.serviceName} -n ${env.buName}"
                            break
                        case "no":
                            break
                    }
                }
            }
        }

    }

    post {
        always {
            // clean workspace build
            cleanWs()
        }
    }

}