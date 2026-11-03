# Decisões de projeto

Registro curto das decisões que moldam o comportamento do app.

## 1. Dados no aparelho primeiro (offline-first)
Todo registro é gravado localmente antes de qualquer envio. O Drive é um complemento. Falha de rede nunca perde dado local.

## 2. Nunca sobrescrever sem cópia e sem decisão
Antes de **substituir a nuvem** ou **restaurar para o aparelho**, o app cria uma cópia datada do lado que seria perdido.
Se a cópia falhar, a operação **não acontece**. A sincronização fica **pausada** até o tutor decidir quando as duas pontas têm dados diferentes.

## 3. Operações destrutivas só "concluem" quando confirmadas
"Apagar tudo" exige digitar uma palavra de confirmação, é validado no ViewModel (não só no botão) e informa **"NÃO foi concluído"**
se algo falhar, podendo ser repetido com segurança.

## 4. Backup do Android desligado
Os dados são sensíveis e já têm backup próprio e opcional (Drive do tutor). Por isso `backup_rules.xml` e `data_extraction_rules.xml`
excluem tudo do backup em nuvem do Android. Na transferência direta entre aparelhos, o estado da conexão com o Drive não é copiado.

## 5. Alarmes diários dos remédios
- Um alarme por horário de cada remédio **ativo**, a partir do texto de horários (`08:00, 20:00`). Texto que não é horário (por exemplo "Livre") não gera alarme.
- Ao disparar, o app confere no banco se o remédio ainda está ativo e dentro do período, avisa e agenda o mesmo horário para o dia seguinte.
- Encerrar, apagar, restaurar backup ou "Apagar tudo" cancelam/reagendam os alarmes. Reinício, atualização do app e mudança de fuso/hora reagendam.
- O aviso **não registra a dose**. Sem permissão de alarme exato, o Android pode atrasar o aviso.
- Limite de 12 horários por dia por remédio.

## 6. Limites de entrada
Glicemia entre 10 e 800 mg/dL, peso entre 0,05 e 150 kg, ano de 1900 a 2100. Servem para barrar erros de digitação, **não** são faixas clínicas
e precisam de revisão veterinária.

## 7. Perfil e fotos
O app nasce com um perfil neutro ("Meu pet") e uma imagem genérica. Nenhuma foto ou dado de pet vem embutido no APK.

## 8. Dependências enxutas
Só o que o código usa. Atualizações semanais sugeridas pelo Dependabot, validadas pela CI.
