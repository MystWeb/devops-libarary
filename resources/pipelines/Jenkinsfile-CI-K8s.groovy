// 加载共享库
@Library("mylib@main") _

// 导入库
import org.devops.*

// New实例化
def checkout = new Checkout()
def build = new Build()
def notice = new Notice()
def unitTest = new UnitTest()
def custom = new Custom()
def codeScan = new CodeScan()
def gitlab = new GitLab()
def artifact = new Artifact()
def docker = new Docker()
def kubernetes = new Kubernetes()

// 流水线
pipeline {
    agent { label "build" }

    options {
        skipDefaultCheckout true
        buildDiscarder logRotator(daysToKeepStr: '30', numToKeepStr: '30')
    }

    parameters {
        string defaultValue: 'http://192.168.20.194/devops/devops-maven-service.git', description: '仓库地址', name: 'srcUrl'
        string defaultValue: 'RELEASE-1.1.1', description: '分支名称', name: 'branchName'
        string defaultValue: 'f0b54c03-789d-4ca4-847d-29f83236ef8a', description: '访问凭据-GitLab', name: 'credentialsId'
        choice choices: ['maven', 'custom', 'mavenSkip', 'gradle', 'ant', 'go', 'npm', 'yarn'], description: '构建类型', name: 'buildType'
        string defaultValue: '', name: 'customBuild', description: '自定义构建命令（示例：mvn clean package -Dpmd.skip=true -Dcheckstyle.skip=true -DskipTests && mvn test）'
        choice choices: ['false', 'true'], description: '是否跳过代码扫描', name: 'skipSonar'
    }

    stages {
        stage("Checkout") {
            steps {
                script {
                    println("Checkout")
                    checkout.GetCode("${env.srcUrl}", "${env.branchName}", "${env.credentialsId}")
                }
            }
        }

        stage("Global") {
            steps {
                script {
                    // 任务名称截取构建类型（任务名称示例：devops-maven-service）
//                    env.buildType = "${JOB_NAME}".split("-")[1]
                    // Git提交ID
                    env.commitId = gitlab.GetShortCommitIdByEightDigit()
                    // JOB任务前缀（业务名称/组名称）
                    env.buName = "${JOB_NAME}".split('-')[0]
                    // 服务/项目名称
                    env.serviceName = "${JOB_NAME}".split('_')[0]
                    // 服务版本号（推荐定义："${branchName}-${commitId}"）
                    env.version = "${env.branchName}-${env.commitId}"

                    // 制品仓库地址
                    env.artifactRegistry = "192.168.20.194:8081"
                    // 制品仓库访问凭据Id
                    env.artifactCredentialsId = "0cbf60e3-319d-464a-8efe-cf83ebeb97ff"
                    // 制品仓库名称
                    env.artifactRepository = "devops-local"
                    // 镜像仓库地址
                    env.imageRegistry = "192.168.20.194:8088"
                    // 镜像仓库访问凭据Id
                    env.imageRegistryCredentialsId = "ef5a1de1-0840-4b51-a0b0-dc04f98544f3"
                    // SonarQube访问凭据Id
                    env.sonarqubeCredentialsId = "05d7379e-28a6-4dd2-9b35-1f907a1a05c8"
                    // GitLab用户（Admin）Token访问凭据Id
                    env.gitlabUserTokenCredentialsId = "d1bc4a72-508e-46c6-8fbd-0ec0fae4e001"
                    // Git项目Id
                    env.projectId = gitlab.GetProjectId("${env.gitlabUserTokenCredentialsId}", "${env.buName}", "${env.serviceName}")

                    // 修改Jenkins构建描述
                    currentBuild.description = """ branchName：${env.branchName} \n commitId：${env.commitId} """
                    // 修改Jenkins构建名称
                    currentBuild.displayName = "${env.version}"
                }
            }
        }

        stage("Build") {
            steps {
                script {
                    println("Build")
                    if (null == "${env.customBuild}" || "${env.customBuild}".trim().length() <= 0) {
                        build.CodeBuild("${env.buildType}")
                    } else {
                        custom.CustomCommands("${env.customBuild}")
                    }
                }
            }
        }

        stage("UnitTest") {
            steps {
                script {
                    if ("${env.buildType}" == "custom") {
                        println("构建类型为：custom，跳过UnitTest阶段，如需单元测试请使用符号：&& 拼接命令")
                    } else {
                        println("UnitTest")
                        unitTest.CodeTest("${env.buildType}")
                    }
                }
            }
        }

        stage("CodeScan") {
            when {
                environment name: 'skipSonar', value: 'false'
            }
            steps {
                script {
                    println("CodeScan")
                    // sonar-init
                    codeScan.InitQualityProfiles("java", "${env.serviceName}", "${env.buName}")
                    // 代码扫描 commit-status
                    codeScan.CodeScan_Sonar("${env.sonarqubeCredentialsId}", "${env.gitlabUserTokenCredentialsId}",
                            "${env.branchName}", "${env.commitId}", "${env.projectId}")
                }
            }
        }

        // 上传制品（Format：raw）
        stage("PushArtifact") {
            steps {
                script {
                    // Dir：/buName/serviceName/branch-version/serviceName-version.suffix
                    // target/demo-0.0.1-SNAPSHOT.jar
                    env.jarName = sh returnStdout: true, script: 'ls target | grep -E "jar\$"'
                    env.fileName = env.jarName - "\n"
                    env.fileSuffix = env.fileName.split('\\.')[-1]
                    env.newFileName = "${env.serviceName}-${env.version}.${env.fileSuffix}"
                    // 重命名制品文件
                    sh "cd target ; mv ${env.fileName} ${env.newFileName}"
                    // 上传制品
                    artifact.PushArtifactByApi("${env.artifactRegistry}", "${env.artifactCredentialsId}", "${env.artifactRepository}",
                            "${env.buName}/${env.serviceName}/${env.version}", "target", "${env.newFileName}")
                }
            }
        }

        stage("DockerBuild") {
            steps {
                script {
                    // imageTag："${env.branchName}-${env.commitId}"
                    env.imageName = "${env.buName}/${env.serviceName}"
                    docker.DockerBuildAndPushImage("${env.imageRegistry}", "${env.imageRegistryCredentialsId}",
                            "${env.imageName}", "${env.version}", "target", "${env.newFileName}")
                }
            }
        }

        stage("K8sReleaseFile") {
            steps {
                script {
                    // Git项目Id（devops-k8s-deployment）
                    k8sProjectId = gitlab.GetProjectId("${env.gitlabUserTokenCredentialsId}", "devops", "devops-k8s-deployment")
                    // Git文件模板名称
                    fileName = "k8s-deployments-template.yaml"
                    // Git上传文件路径：项目服务名称/版本号.yaml
                    filePath = "${env.serviceName}%2f${env.version}.yaml"
                    // 下载Kubernetes部署模板文件
                    fileData = gitlab.GetRepositoryFile("${env.gitlabUserTokenCredentialsId}", "${k8sProjectId}",
                            "${fileName}", "main")
                    // imagePath：镜像仓库地址/镜像名称:镜像标签
                    imagePath = "${env.imageRegistry}/${env.imageName}:${env.version}"
                    // Kubernetes发布模板文件内容替换并转换Base64
                    base64Content = kubernetes.K8sReleaseTemplateFileReplaceAndConvertToBase64("${fileName}", "${fileData}", "${imagePath}")

                    // 上传替换后的版本文件（新建文件或者更新文件）
                    // gitlab文件内容变更（URL编码转义符： %2f = / ）
                    try {
                        gitlab.CreateRepositoryFile("${env.gitlabUserTokenCredentialsId}", "${k8sProjectId}", "main", "${filePath}", "${base64Content}")
                    } catch (e) {
                        gitlab.UpdateRepositoryFile("${env.gitlabUserTokenCredentialsId}", "${k8sProjectId}", "main", "${filePath}", "${base64Content}")
                    }
                }
            }
        }

    }

    post {
        always {
            // Delete workspace when build is done
            cleanWs()
        }
    }

}