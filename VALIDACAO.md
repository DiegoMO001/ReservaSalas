# Validação — ReservaSalasSimplificada / protocolo 2.4.0

Verificação realizada em 10/10/2026 no Windows, com Java 18.0.2.1, Ant 1.10.17, Gson 2.14.0 e SQLite JDBC 3.53.2.0.

## Referência consultada

O YAML foi obtido diretamente do repositório que publica a documentação indicada pelo usuário:

- [asyncapi.yaml](https://github.com/pedrosalvatori/protocolo-reserva-salas/blob/main/asyncapi.yaml), versão declarada **2.4.0**.
- [README e testador oficial](https://github.com/pedrosalvatori/protocolo-reserva-salas#testar-o-servidor-de-voc%C3%AAs).
- SHA-256 do YAML consultado: `d5690a81b09bbf4d9c8cafb994f11c4a8291ef513915141471b3cfceaadfbd51`.

O escopo considera os itens a–l e mantém a administração de usuários já existente. Salas, reservas e disponibilidade continuam ausentes.

## Resultado por requisito

| Cliente / servidor | Operação | Resultado |
|---|---|---|
| a / g | Cadastro comum | Aprovado; aceita emails institucionais e impede duplicidade; perfil sempre user. |
| b / h | Leitura do próprio cadastro | Aprovado; identifica pelo token e não devolve senha, hash ou salt. |
| c / i | Login | Aprovado; ordem 400 → 401 → 409; uma única sessão nos logins concorrentes. |
| d / j | Logout | Aprovado; invalida a sessão; reconecta com o mesmo token após inatividade; aguarda resposta. |
| e / k | Atualização própria | Aprovado; email imutável, token preservado, atualização totalmente vazia retorna 400. |
| f / l | Exclusão própria | Aprovado; senha incorreta retorna 403 e mantém sessão; exclusão remove cadastro e sessões. |

A administração também foi verificada, incluindo atualização vazia, autorização, exclusão, sessão invalidada, reutilização de nome/email e proteção do último administrador.

## Testador oficial

**45 testes passaram, 0 falharam, 28 foram pulados, de 73 casos selecionados pelo roteiro.**

Foi usada uma cópia do testador oficial com um único filtro antes da execução de cada caso: pular os grupos Salas e Reservas e a limpeza das reservas. As requisições, expectativas, textos de resposta e validação contra o YAML dos testes de usuários não foram alterados. As operações condicionais que dependem de uma sala criada também não são executadas. O resultado integral está em `VALIDACAO-PROTOCOLO.txt`.

O teste utilizou um servidor novo em pasta temporária, com banco próprio. Não acessou contas ou banco operacionais.

## Testes locais

Construção e execução concluíram com **BUILD SUCCESSFUL**, código de saída 0:

- `TestesUsuarios`: **104 verificações aprovadas**. Validação de email novo, persistência, hashes e salts, sessões, exclusão física, unicidade, expiração, concorrência e migração do banco anterior.
- `TestesIntegracao`: **2.412 verificações aprovadas**. TCP, fluxos reais da CLI, cadastro com email institucional, atualização vazia, senha de exclusão incorreta, permissões, protocolo, múltiplas conexões e operações removidas.
- `TestesProtocolo24`: **49 verificações aprovadas**. Logs de envio e recebimento, arquivo igual ao console, proteção de senhas/tokens, continuidade dos arquivos entre execuções, falha de gravação sem encerrar a aplicação e logout com reconexão.

As contagens são de asserções, incluindo campos das respostas, listagens e registros de log; não representam milhares de cenários independentes.

O teste de inatividade reduz o timeout do servidor de teste para 150 ms e verifica fechamento do socket, reenvio de logout com o mesmo token, invalidação e novo login bem-sucedido. O timeout de produção continua em **300 segundos**. Um servidor de teste adicional simula logout com erro 500, resposta perdida e resposta 401 no reenvio, confirmando que a CLI não abandona a sessão antecipadamente.

A migração foi testada sobre a estrutura anterior: contas e sessões ativas permanecem; sessões órfãs antigas são removidas; a exclusão posterior apaga as sessões vinculadas.

## Execução dos programas e logs

`ServidorMain` e `ClienteMain` foram executados em processos separados. O roteiro a–l completo passou, com encerramento por `sair`. Os arquivos `cliente-log.txt` e `servidor-log.txt` foram encontrados na pasta isolada de execução, contendo todas as respostas do roteiro sem senhas em texto aberto.

O teste drena continuamente a saída dos dois processos para que o volume de registros não bloqueie os pipes de captura.

Os logs são locais. O protocolo continua tendo exatamente uma resposta JSON por solicitação. A aplicação não envia registros de diagnóstico pelo socket.

## Reprodução

Na pasta do projeto, com JDK e Ant configurados:

```bat
ant clean test
```

Os testes usam arquivos exclusivos em `build/test-data`, sem acessar `sistema.db`. Para verificar manualmente o programa e os logs, siga o roteiro no README.

O ambiente restrito desta sessão emitiu `AccessDeniedException` do JDK ao fechar arquivos JAR, mas compilou, gerou o executável e concluiu as três suítes. O JAR resultante também passou no testador oficial e na execução dos programas em processos separados. A interface do NetBeans não foi aberta; a construção foi verificada pelo Ant.
