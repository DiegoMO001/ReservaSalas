package comum;

public final class Mensagens {
    public static final String E400_requisicao = "Requisicao invalida";
    public static final String E400_operacao = "Operacao desconhecida";
    public static final String E400_tamanho = "Mensagem excede o tamanho maximo";
    public static final String E400_cadastro = "Dados de cadastro em formato invalido";
    public static final String E400_credenciais = "Email ou senha em formato invalido";
    public static final String E400_token = "Token em formato invalido";
    public static final String E400_dados = "Dados em formato invalido";
    public static final String E400_senha = "Senha em formato invalido";
    public static final String E400_nada_para_atualizar = "Nenhum dado para atualizar";
    public static final String E401_credenciais = "Email ou senha incorretos";
    public static final String E401_token = "Token invalido ou expirado";
    public static final String E403_senha_incorreta = "Senha incorreta";
    public static final String E403_permissao = "Permissao insuficiente";
    public static final String E403_ultimo_admin = "Nao e possivel remover o ultimo administrador";
    public static final String E404_usuario = "Usuario nao encontrado";
    public static final String E409_cadastrado = "Usuario ou email ja cadastrado";
    public static final String E409_sessao_ativa = "Usuario ja possui sessao ativa";
    public static final String E409_usuario_em_uso = "Usuario ja esta em uso";
    public static final String E500 = "Erro interno do servidor";
    public static final String CADASTRO = "Usuario cadastrado com sucesso";
    public static final String LOGIN = "Login realizado com sucesso";
    public static final String LOGOUT = "Logout realizado com sucesso";
    public static final String CONSULTA = "Consulta realizada com sucesso";
    public static final String USUARIO_ATUALIZADO = "Dados atualizados com sucesso";
    public static final String USUARIO_REMOVIDO = "Usuario removido com sucesso";
    public static final String ADMIN_INICIAL =
            "Admin inicial criado: user=admin senha=admin123 (altere apos o primeiro login)";

    private Mensagens() {
    }
}
