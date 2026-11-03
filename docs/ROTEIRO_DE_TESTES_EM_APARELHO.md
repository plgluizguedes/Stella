# Roteiro de testes em aparelho real

O CI roda testes na JVM. Os itens abaixo só podem ser comprovados em um celular. Registre **data, aparelho, versão do Android,
resultado esperado × obtido** e, se possível, capturas de tela (sem dados reais). Marque `PASSOU`, `FALHOU` ou `NÃO EXECUTADO`.

## A. Restauração em aparelho limpo
1. Em um aparelho com dados, ative o backup no Drive e sincronize.
2. Em outro aparelho (ou após desinstalar o app), instale o APK e entre com a mesma conta.
3. Restaure da nuvem.
4. Confira: perfil, glicemias, remédios, doses, consultas, exames, vacinas, pesos, lembretes, **fotos e anexos abrem**.
5. Esperado: nenhum registro perdido; relatório parcial só se algum anexo realmente faltar.

## B. Alarmes diários de remédio
1. Cadastre um remédio com horário daqui a 2 minutos. Espere: notificação "Hora do remédio" no horário.
2. Confirme que a dose **não** foi registrada sozinha.
3. No dia seguinte (ou ajustando o relógio), confirme que tocou de novo.
4. Reinicie o aparelho antes do próximo horário: o alarme ainda toca.
5. Encerre o remédio: não toca mais. Reative: volta a tocar. Apague: não toca.
6. Mude o fuso horário: o alarme passa a tocar no novo horário local.
7. Atualize o app por cima (mesma assinatura): os alarmes continuam.
8. Negue a permissão de notificação e depois a de alarme exato (Android 12+): anote o comportamento.
9. Repita em pelo menos 2 aparelhos/marcas (algumas limitam alarmes em segundo plano).

## C. Texto digitado
Abra um diálogo (ex.: nova consulta), digite, gire a tela/troque de app e volte. Anote se o texto permanece.

## D. Acessibilidade
Com TalkBack, percorra cadastro, medição de glicemia e relatório. Com fonte em 200 %, confira se nada é cortado.

## E. Apagar tudo
Apague no aparelho, depois verifique: sem registros, sem fotos, sem alarmes tocando.
