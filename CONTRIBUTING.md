# Contribuindo com o Stella

Obrigado pelo interesse! Antes de começar:

1. **Abra uma *issue*** descrevendo o problema ou a ideia, para alinharmos antes de você investir tempo.
2. **Nunca inclua dados reais** (de pets, tutores, clínicas, contas Google) em issues, PRs, capturas de tela ou logs.
3. **Nunca versione segredos:** keystores, senhas, tokens, `google-services.json`, `.env`.

## Fluxo

- Crie uma branch a partir da `main` e abra um *pull request* pequeno e focado.
- O PR precisa passar na CI (`./gradlew :app:testDebugUnitTest`). Funcionalidades novas e correções precisam de teste quando couber.
- Mudanças que tocam **backup, restauração, apagar dados ou alarmes** exigem cuidado extra: descreva o que foi testado e o que **não** foi.
- Seja honesto no PR: separe o que foi comprovado em aparelho real do que só compilou ou passou em testes de JVM.

## Padrões

- Kotlin com o estilo oficial; textos da interface em português do Brasil.
- Nenhuma operação destrutiva sem confirmação e sem cópia de segurança.
- Dados de saúde são sensíveis: sem *analytics*, sem rede além do Google Drive opcional.

## Ambiente

JDK 21, Android Studio recente e Android SDK 36. Veja o [README](README.md#começando-para-quem-quer-compilar).
