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
def nexus = new Nexus()

// 任务名称截取构建类型（任务名称示例：devops-maven-service）
// env.buildType = "${JOB_NAME}".split("-")[1]

// 流水线
pipeline {
    agent { label "build" }

    options {
        skipDefaultCheckout true
    }

    parameters {
        string defaultValue: 'http://192.168.20.197/devops/devops-maven-service.git', description: '仓库地址', name: 'srcUrl'
        string defaultValue: 'main', description: '分支名称', name: 'branchName'
        string defaultValue: 'f0b54c03-789d-4ca4-847d-29f83236ef8a', description: '访问凭据-GitLab', name: 'credentialsId'
        choice choices: ['custom', 'maven', 'mavenSkip', 'gradle', 'ant', 'go', 'npm', 'yarn'], description: '构建类型', name: 'buildType'
        string defaultValue: '', name: 'customBuild', description: '自定义构建命令（示例：mvn clean package -Dpmd.skip=true -Dcheckstyle.skip=true -DskipTests && mvn test）'
        choice choices: ['false', 'true'], description: '是否跳过代码扫描', name: 'skipSonar'
        choice choices: ['release', 'snapshot', 'skip'], description: '制品仓库：RELEASE类型仓库（存放制品稳定版），SNAPSHOT类型仓库（存放制品开发版）', name: 'repositoryType'
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
                    profileName = "${JOB_NAME}".split("-")[0]
                    codeScan.InitQualityProfiles("java", "${JOB_NAME}", profileName)
                    // commit-status
                    groupName = profileName
                    commitId = gitlab.GetCommitId()
                    projectId = gitlab.GetProjectId(groupName, "${JOB_NAME}")
                    // 代码扫描
                    codeScan.CodeScan_Sonar("${env.branchName}", commitId, projectId)
                }
            }
        }

        stage("PushArtifact") {
            when {
                anyOf {
                    environment name: 'repositoryType', value: 'release'
                    environment name: 'repositoryType', value: 'snapshot'
                }
            }
            steps {
                script {
                    // 解析Maven pom.xml 文件
                    pomData = readMavenPom file: 'pom.xml'
                    // pomData = com.example:demo:jar:0.0.1-SNAPSHOT
                    // 拼接 RepositoryName、制品文件路径
                    buName = "${JOB_NAME}".split('-')[0]
                    repository = "${buName}-${env.repositoryType}"
                    // target/demo-0.0.1-SNAPSHOT.jar
                    filePath = "target/${pomData.artifactId}-${pomData.version}.${pomData.packaging}"
                    // 上传制品
                    nexus.PushArtifactByNexusPlugin(pomData.artifactId, filePath, pomData.packaging,
                            pomData.groupId, repository, pomData.version)
                }
            }
        }

    }

}