# ReservaSalasSimplificada

Sistema de console em Java para cadastro e gerenciamento de usuários por TCP, conforme o protocolo de mensagens **2.4.0**. A aplicação possui cliente CLI, servidor TCP, persistência SQLite e Gson 2.14.0.

Esta distribuição atende exclusivamente às operações de usuários. Não há cadastro, consulta, alteração ou remoção de salas, reservas ou disponibilidade.

## Protocolo

[Protocolo](https://pedrosalvatori.github.io/protocolo-reserva-salas/#/salas/read_room)

## Cliente e servidor

Sem login, o cliente oferece: 
	- **1 Cadastrar**
	- **2 Login**
	- **0 Sair**

Com login, oferece: 
	- **9 Ver minha conta**
	- **10 Atualizar minha conta**
	- **11 Remover minha conta**
	- **12 Logout**
	- **0 Sair**

Para administradores, oferece:
	- **24 Listar usuários**
	- **25 Ler usuário**
	- **26 Atualizar usuário**
	- **27 Remover usuário**

O servidor cria automaticamente o administrador inicial com email `admin@campus.com` e senha `admin123`. Altere essa senha após o primeiro acesso.

## Comunicação visível e logs

Cada troca aparece no CLI do cliente e do servidor com direção, operação, status e identificação da troca. Reconexões, respostas não confirmadas e falhas de comunicação também são informadas.

Os arquivos são criados na pasta de execução: `cliente-log.txt` registra as mensagens do cliente e `servidor-log.txt` registra as mensagens do servidor. Os arquivos usam UTF-8 e recebem novos registros sem apagar os anteriores. Senhas, hashes e salts aparecem como `[oculto]`; tokens são registrados somente com os oito primeiros caracteres. O conteúdo bruto de mensagens inválidas não é gravado.

Exemplo:

```text
2026-10-14 14:00:00 [CLIENTE pid=1234] ENVIADO C -> S troca=3 tentativa=1 {"op":"read_user","token":"1234abcd..."}
2026-10-14 14:00:00 [CLIENTE pid=1234] RECEBIDO S -> C troca=3 {"op":"read_user_response","status":"200","message":"Consulta realizada com sucesso","user":"joao","email":"joao@email.com","role":"user","created_at":"2026-10-14 13:59:00"}
```

Os logs são registros locais e não alteram o protocolo TCP nem acrescentam mensagens ao socket.

## Execução no Windows

1. Extraia o projeto e abra a pasta `ReservaSalasSimplificada`.
2. Instale o JDK 18 ou superior e deixe `java` disponível no PATH.
3. Execute `servidor.bat`. Pressione Enter para usar a porta 12345.
4. Em outra janela, execute `cliente.bat`. Pressione Enter para usar `127.0.0.1` e a porta 12345.
5. Use o menu do cliente.

O banco `sistema.db` é criado na pasta de execução do servidor. Os logs são criados na mesma pasta. Para encerrar normalmente, escolha **Logout** ou **0 Sair** no cliente e digite `sair` no servidor.

Comandos equivalentes:

```bat
java -cp "dist\ReservaSalasSimplificada.jar;dist\lib\*" servidor.ServidorMain
```

```bat
java -cp "dist\ReservaSalasSimplificada.jar;dist\lib\*" cliente.ClienteMain
```

Para usar outro computador, informe no cliente o IP do computador que executa o servidor e a mesma porta TCP.

## NetBeans e Ant

Abra a pasta `ReservaSalasSimplificada` no NetBeans. O projeto contém `nbproject` e `build.xml`, usa Java 18 e já referencia `lib/gson-2.14.0.jar` e o driver SQLite.

Com Ant instalado, execute na pasta do projeto:

```bat
ant clean test
ant run-server
```

Em outro terminal:

```bat
ant run-client
```

Ou, se preferir, execute diretamente as classes ServidorMain.java e ClienteMain.java. Inicie primeiro o ServidorMain e, em seguida, execute o ClienteMain em outra execução do projeto.

Os testes criam bancos temporários em `build/test-data` e não usam o banco operacional.

## Estrutura do projeto

- `src/cliente`: menus, formulários, comunicação, reconexão e logs do cliente.
- `src/comum`: mensagens, validação, JSON, datas, framing e registro das trocas.
- `src/servidor`: servidor TCP, conexões, threads, autorização e logs do servidor.
- `src/servidor/services`: autenticação e regras de usuários.
- `src/servidor/dao`: persistência de usuários e sessões.
- `src/servidor/modelo`: usuário, sessão e hash de senha.
- `test`: testes de protocolo, persistência, concorrência, cliente e servidor.

O banco da aplicação contém as tabelas `users` e `sessions`. O token é mantido em memória pelo cliente e persistido pelo servidor para permitir reconexão entre sockets.