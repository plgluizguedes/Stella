# Segurança

## Como reportar uma vulnerabilidade

Use o recurso **"Report a vulnerability"** da aba *Security* deste repositório (relato privado). Não abra *issue* pública com detalhes de uma falha.

## O que o projeto faz por segurança

- Somente HTTPS; escopo mínimo do Google Drive (`drive.file`); nenhum segredo de servidor no app.
- Backup automático do Android desligado para os dados do app.
- Consultas ao banco parametrizadas (Room); `FileProvider` não exportado.
- Segredos locais (`.env`, keystores, `google-services.json`) estão no `.gitignore`.

## Limites conhecidos

- O banco local **não é criptografado** e o app **não tem bloqueio** por PIN/biometria; quem desbloqueia o aparelho acessa os dados.
- Builds de teste são assinados com chave de debug e **não** servem como versão pública.

## Para quem contribui

- Nunca versione chaves, senhas, keystores, tokens ou dados reais de saúde/pessoais.
- Não inclua dados reais em *issues*, *pull requests*, capturas de tela ou logs.
