def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
    def args = [:]
    args.BUILD_NUMBER  = env.BUILD_NUMBER
    args.BUILD_URL     = env.BUILD_URL
    args.JOB_BASE_NAME = env.JOB_BASE_NAME
    args.JOB_NAME      = env.JOB_NAME
    args.JOB_URL       = env.JOB_URL
    args.chatFeedbackChannels     = (env.CANAIS_NOTIFICACAO                         ?: config.canaisNotificacao    ?: "").trim()
    args.buildPodLabel            = helper.getPythonBuildPod(config)
    args.buildDocker              = (config.habilitarEmpacotamentoDocker == null    ?  false                        : config.habilitarEmpacotamentoDocker)
    args.timeout                  = (config.timeout == null                         ? 45                            : config.timeout)
    args.runReleaseEngileAnalysis = false
    args.securityPreset = ""
    args.surveyId = 'BykM6tI4gUGHULCxkLYEQx96V2lbuMBFna2h-FZEwJBUOElLR1hLMlc4TURISUsySkZaV1dVN0lFTC4u'
    helper.getJobParameters(args, config)

    def targetForRun = helper.getTargetForRun(config)
    def podImage = helper.getTemplateImages(targetForRun, args)

    timeout(args.timeout){
        if ((!targetForRun) || (targetForRun=='kubernetes')) {
            // Inicio - Kubernetes
            pipeline {
                agent {
                    kubernetes {
                        activeDeadlineSeconds 60
                        cloud 'kubernetes'
                        defaultContainer 'jnlp'
                        idleMinutes 0
                        label "${args.buildPodLabel}${args.labelSufix}"
                        yaml "${podImage}"
                    }
                }
                stages {
                    stage('Inicialização & Preparação') {
                        steps {
                            script {
                                sh 'echo iniciando'
                                python.prepare(args)
                                psc.cleanDockerEngine()
                            }
                        }
                    }
                    stage('Validação dos pré-requisitos') {
                        steps {
                            script {
                                python.validatePreReq(args)
                            }
                        }
                    }
                    stage('Validação estática do código Python (flake8)') {
                        when {
                            expression {
                                return (
                                    args.buildRunStaticValidation
                                )
                            }
                        }
                        steps {
                            script {
                                python.validateCodeStaticaly(args)
                            }
                        }
                    }
                    stage('Validação estática do Dockerfile (hadolint)') {
                        when {
                            expression {
                                return (
                                    args.buildDocker
                                )
                            }
                        }
                        steps {
                            script {
                                psc.validateCodeStaticaly(args)
                            }
                        }
                    }
                    stage('Execução dos Testes de Segurança do Código Python (bandit)') {
                        when {
                            expression {
                                return (
                                    args.buildRunSecurityValidation
                                )
                            }
                        }
                        steps {
                            script {
                                python.validateCodeSecurity(args)
                            }
                        }
                    }
                    stage('Execução dos Testes de Unidade do Código Python (pytest)') {
                        when {
                            expression {
                                return (
                                    args.buildRunUnitTests
                                )
                            }
                        }
                        steps {
                            script {
                                python.runUnitTests(args)
                            }
                        }
                    }
                    stage('Execução dos Testes de Integração do Código Python (pytest)') {
                        when {
                            expression {
                                return (
                                    args.buildRunIntegrationTests
                                )
                            }
                        }
                        steps {
                            script {
                                python.runIntegrationTests(args)
                            }
                        }
                    }
                    stage('Avaliação do Checkmarx') {
                        when {
                            expression {
                                return (
                                    args.gitBranch == 'origin/master'
                                )
                            }
                        }
                        steps {
                            script {
                                checkmarx(args,config)
                            }
                        }
                    }
                    stage('Avaliação do Sonar') {
                        when {
                            expression {
                                return (args.buildRunSonar)
                            }
                        }
                        steps {
                            script {
                                python.runSonar(args)
                                args.runReleaseEngileAnalysis = true
                            }
                        }
                    }
                    stage('Empacotamento do código para geração do módulo Python') {
                        when {
                            expression {
                                return (
                                    args.buildPackage
                                )
                            }
                        }
                        steps {
                            script {
                                python.createPackage(args)
                            }
                        }
                    }
                    stage('Construção da Imagem Docker (docker build)') {
                        when {
                            expression {
                                return (
                                    args.buildDocker
                                )
                            }
                        }
                        steps {
                            script {
                                psc.compile(args)
                            }
                        }
                    }
                    stage('Execução dos testes de fumaça da Imagem Docker') {
                        when {
                            expression {
                                return (
                                    args.buildDocker &&
                                    args.buildRunSmokeTests
                                )
                            }
                        }
                        steps {
                            script {
                                psc.runSmokeTests(args)
                            }
                        }
                    }
                    stage('Publicação do módulo Python no repositório de binários') {
                        when {
                            expression {
                                return (
                                    args.buildPackage &&
                                    args.buildPublish
                                )
                            }
                        }
                        steps {
                            script {
                                python.publish(args)
                            }
                        }
                    }
                    stage('Publicação da Imagem Docker (docker push)') {
                        when {
                            expression {
                                return (
                                    args.buildDocker
                                )
                            }
                        }
                        steps {
                            script {
                                psc.publish(args)
                            }
                        }
                    }
                    stage('Submete avaliação ao Motor de Liberação') {
                        when {
                            expression {
                                return (args.runReleaseEngineAnalysis)
                            }
                        }
                        steps {
                            script {
                                releaseEngine(args)
                            }
                        }
                    }
                }
                post {
                    failure {
                        script {
                            helper.runPostFailure(args)
                            logViewer(args, 'falhou', 'python')
                            helper.satisfactionSurvey(args)
                        }
                    }
                    success {
                        script {
                            helper.runPostSuccess(args)
                            logViewer(args, 'sucesso', 'python')
                            helper.satisfactionSurvey(args)
                        }
                    }
                }
            }
        } else {
            pipeline {
                agent {
                    docker {
                        image "${podImage}"
                        label "${targetForRun}"
                    }
                }
                stages {
                    stage('Inicialização & Preparação') {
                        steps {
                            sh 'echo iniciando'
                            script {
                                python.prepare(args)
                                psc.cleanDockerEngine()
                            }
                        }
                    }
                    stage('Validação dos pré-requisitos') {
                        steps {
                            script {
                                python.validatePreReq(args)
                            }
                        }
                    }
                    stage('Validação estática do código Python (flake8)') {
                        when {
                            expression {
                                return (
                                    args.buildRunStaticValidation
                                )
                            }
                        }
                        steps {
                            script {
                                python.validateCodeStaticaly(args)
                            }
                        }
                    }
                    stage('Validação estática do Dockerfile (hadolint)') {
                        when {
                            expression {
                                return (
                                    args.buildDocker
                                )
                            }
                        }
                        steps {
                            script {
                                psc.validateCodeStaticaly(args)
                            }
                        }
                    }
                    stage('Execução dos Testes de Segurança do Código Python (bandit)') {
                        when {
                            expression {
                                return (
                                    args.buildRunSecurityValidation
                                )
                            }
                        }
                        steps {
                            script {
                                python.validateCodeSecurity(args)
                            }
                        }
                    }
                    stage('Execução dos Testes de Unidade do Código Python (pytest)') {
                        when {
                            expression {
                                return (
                                    args.buildRunUnitTests
                                )
                            }
                        }
                        steps {
                            script {
                                python.runUnitTests(args)
                            }
                        }
                    }
                    stage('Execução dos Testes de Integração do Código Python (pytest)') {
                        when {
                            expression {
                                return (
                                    args.buildRunIntegrationTests
                                )
                            }
                        }
                        steps {
                            script {
                                python.runIntegrationTests(args)
                            }
                        }
                    }
                    stage('Avaliação do Checkmarx') {
                        when {
                            expression {
                                return (
                                    args.gitBranch == 'origin/master'
                                )
                            }
                        }
                        steps {
                            script {
                                checkmarx(args,config)
                            }
                        }
                    }
                    stage('Avaliação do Sonar') {
                        when {
                            expression {
                                return (args.buildRunSonar)
                            }
                        }
                        steps {
                            script {
                                python.runSonar(args)
                                args.runReleaseEngileAnalysis = true
                            }
                        }
                    }
                    stage('Empacotamento do código para geração do módulo Python') {
                        when {
                            expression {
                                return (
                                    args.buildPackage
                                )
                            }
                        }
                        steps {
                            script {
                                python.createPackage(args)
                            }
                        }
                    }
                    stage('Construção da Imagem Docker (docker build)') {
                        when {
                            expression {
                                return (
                                    args.buildDocker
                                )
                            }
                        }
                        steps {
                            script {
                                psc.compile(args)
                            }
                        }
                    }
                    stage('Execução dos testes de fumaça da Imagem Docker') {
                        when {
                            expression {
                                return (
                                    args.buildDocker &&
                                    args.buildRunSmokeTests
                                )
                            }
                        }
                        steps {
                            script {
                                psc.runSmokeTests(args)
                            }
                        }
                    }
                    stage('Publicação do módulo Python no repositório de binários') {
                        when {
                            expression {
                                return (
                                    args.buildPackage &&
                                    args.buildPublish
                                )
                            }
                        }
                        steps {
                            script {
                                python.publish(args)
                            }
                        }
                    }
                    stage('Publicação da Imagem Docker (docker push)') {
                        when {
                            expression {
                                return (
                                    args.buildDocker
                                )
                            }
                        }
                        steps {
                            script {
                                psc.publish(args)
                            }
                        }
                    }
                    stage('Submete avaliação ao Motor de Liberação') {
                        when {
                            expression {
                                return (args.runReleaseEngineAnalysis)
                            }
                        }
                        steps {
                            script {
                                releaseEngine(args)
                            }
                        }
                    }
                }
                post {
                    failure {
                        script {
                            helper.runPostFailure(args)
                            logViewer(args, 'falhou', 'python')
                            helper.satisfactionSurvey(args)
                        }
                    }
                    success {
                        script {
                            helper.runPostSuccess(args)
                            logViewer(args, 'sucesso', 'python')
                            helper.satisfactionSurvey(args)
                        }
                    }
                }
            }
        }
    }
}