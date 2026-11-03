# Política de privacidade — STELLA

> **Rascunho técnico.** Descreve o que o código faz hoje. Não foi revisado por advogado; antes de distribuir o app a outras pessoas
> (loja de aplicativos, LGPD), esta política precisa de revisão jurídica.

## O que o app guarda

Dados que **o próprio tutor digita ou anexa**: perfil do pet (nome, espécie, raça, nascimento, peso, microchip, observações),
veterinário e clínica, glicemias, medicamentos e doses, consultas, exames e documentos (PDF/JPG/PNG), vacinas, peso, alimentação,
lembretes e fotos. Podem conter dados pessoais do tutor (por exemplo, telefone da clínica ou nome de quem administrou uma dose).

## Onde ficam

- **No aparelho**, em banco local (Room/SQLite) e na pasta privada do app. Outros apps não têm acesso.
- O banco **não é criptografado** e o app **não tem bloqueio** por PIN/biometria.
- O **backup automático do Android está desligado** para os dados do app: nada é copiado para a nuvem do Android.
- Na transferência direta entre aparelhos (cabo/Wi-Fi), banco e anexos podem acompanhar o tutor; o estado da conexão com o Drive não.

## Google Drive (opcional)

- Começa **desligado**. Só funciona se o tutor vincular uma conta Google e acionar a sincronização.
- Escopo `drive.file`: o app só enxerga arquivos que ele mesmo criou.
- Vão para uma pasta do Drive do tutor: o backup completo (`stella_prontuario_backup.json`), um relatório em texto
  (`relatorio_medico_stella.txt`), cópias datadas de segurança e os anexos.
- **Retenção:** as cópias datadas **não são apagadas automaticamente**; ficam até o tutor removê-las. O tutor pode apagar
  tudo pelo app ("Apagar tudo") ou diretamente no Google Drive.
- O app não grava o token do Google; a autenticação é feita pelo Google Play Services.

## O que o app NÃO faz

Sem anúncios, sem *analytics*, sem relatório de falhas, sem inteligência artificial, sem servidor do projeto, sem venda ou
compartilhamento de dados. A única conexão de rede é com o Google Drive, e apenas se o tutor ativar.

## Seus controles

- **Apagar tudo** no aparelho, na nuvem ou em ambos (com confirmações), nas configurações do perfil.
- Desconectar a conta do Drive a qualquer momento.
- Desinstalar o app remove os dados locais do aparelho. Cópias no Drive só saem se o tutor as apagar.
- **Exportar:** por ora, só pelo backup no Drive. Exportação local está planejada.

## Logs

Os registros técnicos do Android (logcat) não incluem e-mail nem títulos de lembretes. Ainda assim, não compartilhe logs publicamente
sem revisar.

## Contato

Abra um relato privado em *Security → Report a vulnerability* para questões de segurança. Não publique dados reais em *issues*.
