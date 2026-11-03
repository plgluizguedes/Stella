# Changelog

Formato inspirado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).

## [Não lançado]

### Adicionado
- Alarmes diários para os horários dos medicamentos ativos (reagendados após reinício, atualização do app e mudança de fuso).
- Limites de valores digitados (glicemia, peso e ano das datas), com testes.
- Documentação: privacidade, decisões de projeto, estado do projeto e roteiro de testes em aparelho.
- Dependabot para dependências Gradle e GitHub Actions.

### Alterado
- Backup automático do Android desligado para os dados do app (explícito em `backup_rules.xml` e `data_extraction_rules.xml`).
- Perfil padrão neutro ("Meu pet") e imagem de foto genérica; nenhuma foto pessoal no app.
- Logs não registram mais e-mail nem título de lembretes.
- `okhttp` atualizado para 4.12.0.

### Removido
- Dependências sem uso: Firebase (AI, App Check), Retrofit, Moshi e logging-interceptor; plugins Google Services e Secrets.
- Projeto Flutter e workflow de CI correspondente (código morto), `metadata.json`, `.env.example`.
- Arquivos de ferramenta de desenvolvimento (`.aistudio/`) e configuração com identificadores de projeto Google.

## [1.0] — versão inicial
- Prontuário offline com Room, Google Drive opcional, lembretes pontuais, relatório e busca global.
