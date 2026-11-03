# Estado do projeto

Visão honesta do que está pronto e do que falta. Legenda: ✅ feito e testado · 🟡 feito, mas **não** provado em aparelho · ⏳ pendente.

## Dados e backup
- ✅ Banco local com migração 1→2 testada.
- ✅ Backup/restauração com cópia de segurança prévia, anexos por SHA-256, falhas parciais reportadas (Drive simulado nos testes).
- ✅ Apagar tudo (aparelho, nuvem ou ambos).
- ⏳ Prova de **restauração em aparelho limpo** (roteiro em [`ROTEIRO_DE_TESTES_EM_APARELHO.md`](ROTEIRO_DE_TESTES_EM_APARELHO.md)).
- ⏳ Exportação local (JSON/PDF/CSV).
- ⏳ Editar registros preservando histórico (hoje só é possível excluir glicemia, peso, dose e consulta).
- ⏳ Banco com chaves estrangeiras, índices e esquema exportado (`exportSchema = true`) — exige migração 2→3 com teste.

## Lembretes
- 🟡 Alarmes diários de remédio (lógica testada na JVM; falta aparelho real, reinício e fuso).
- ⏳ Tela que explica/pede a permissão de alarme exato; notificação que abre o item.
- ⏳ Estados de dose: pular, adiar, "não sei".

## Segurança e privacidade
- ✅ Backup do Android desligado; logs sem e-mail/título; dependências sem uso removidas; `okhttp` 4.12.0.
- ⏳ Criptografia do banco e bloqueio por PIN/biometria.
- ⏳ Migração do Google Sign-In legado para o Credential Manager.
- ⏳ Revisão jurídica da política de privacidade.
- ⏳ Build de release assinado com chave própria, minificação e `applicationId`/variante separando teste de produção.

## Interface
- 🟡 Textos de relatório reformulados como "registro do tutor".
- ⏳ Acessibilidade: descrições em ícones/imagens, alvos de toque, contraste, TalkBack, fonte ampliada.
- ⏳ Preservar texto digitado ao girar a tela; indicador de "sem internet"; confirmação ao salvar.
- ⏳ Responsividade (tablet, paisagem, teclado), tema escuro (o esquema existe mas está desligado).
- ⏳ Avisos de "não substitui o veterinário" e orientação de urgência **revisados por médico-veterinário**.

## Qualidade
- ✅ Testes de unidade/Robolectric com Drive simulado; CI no GitHub Actions.
- ⏳ Testes de interface, de alarme em aparelho, de acessibilidade e de desempenho; lint e varredura de dependências na CI.
- ⏳ Dividir arquivos muito grandes (`GoogleDriveSyncManager`, `StellaViewModel`, `Dialogs`, `VaccinationHistoryScreen`).
- ⏳ Modelo multi-pet e compartilhamento entre cuidadores.
