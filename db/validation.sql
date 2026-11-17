create or replace view v_staging_tipado as
select s.*,
       to_date(inicio_txt default null on conversion error, 'FXYYYY-MM-DD') inicio_data,
       to_date(fim_txt default null on conversion error, 'FXYYYY-MM-DD') fim_data,
       to_number(valor_txt default null on conversion error,
                 '9999999990D99', 'NLS_NUMERIC_CHARACTERS=''.,''') valor_num
from staging_registro s;

create or replace view v_erros_locais as
select lote_id, entidade, linha, campo, codigo_erro
from (
    select lote_id, entidade, linha,
        case when erro_csv is not null then 'FORMATO_CSV' end erro_linha,
        case when codigo is null then 'OBRIGATORIO'
             when not regexp_like(codigo, '^[A-Z0-9][A-Z0-9_-]{0,29}$', 'c') then 'FORMATO' end erro_codigo,
        case when entidade = 'CLIENTE' then
             case when nome is null then 'OBRIGATORIO'
                  when length(nome) > 120 then 'TAMANHO' end end erro_nome,
        case when entidade = 'CLIENTE' then
             case when email is null then 'OBRIGATORIO'
                  when length(email) > 254 then 'TAMANHO'
                  when not regexp_like(email, '^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+$', 'c')
                  then 'FORMATO' end end erro_email,
        case when entidade = 'CONTRATO' then
             case when cliente_codigo is null then 'OBRIGATORIO'
                  when not regexp_like(cliente_codigo, '^[A-Z0-9][A-Z0-9_-]{0,29}$', 'c')
                  then 'FORMATO' end end erro_cliente,
        case when entidade = 'CONTRATO' then
             case when inicio_txt is null then 'OBRIGATORIO'
                  when inicio_data is null or inicio_data < date '1900-01-01'
                       or not regexp_like(inicio_txt, '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', 'c')
                  then 'DATA_INVALIDA' end end erro_inicio,
        case when entidade = 'CONTRATO' then
             case when fim_txt is null then 'OBRIGATORIO'
                  when fim_data is null or fim_data < date '1900-01-01'
                       or not regexp_like(fim_txt, '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', 'c')
                  then 'DATA_INVALIDA'
                  when fim_data < inicio_data and inicio_data >= date '1900-01-01'
                       and regexp_like(inicio_txt, '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', 'c')
                  then 'PERIODO' end end erro_fim,
        case when entidade = 'CONTRATO' then
             case when valor_txt is null then 'OBRIGATORIO'
                  when not regexp_like(valor_txt, '^[0-9]{1,10}([.][0-9]{1,2})?$', 'c')
                       or valor_num is null or valor_num <= 0 then 'VALOR_INVALIDO' end end erro_valor
    from v_staging_tipado
)
unpivot exclude nulls (
    codigo_erro for campo in (
        erro_linha as '_linha', erro_codigo as 'codigo', erro_nome as 'nome',
        erro_email as 'email', erro_cliente as 'cliente_codigo', erro_inicio as 'inicio',
        erro_fim as 'fim', erro_valor as 'valor'
    )
);

create or replace view v_staging_chaves as
select lote_id, entidade, linha, codigo,
       count(*) over (partition by lote_id, entidade, codigo) quantidade
from staging_registro
where codigo is not null;

create or replace view v_erros_lote as
with regras as (
    select lote_id, entidade, linha, campo, codigo_erro from v_erros_locais
    union all
    select lote_id, entidade, linha, 'codigo', 'DUPLICADO'
    from v_staging_chaves where quantidade > 1
    union all
    select s.lote_id, s.entidade, s.linha, 'cliente_codigo', 'ORFAO'
    from staging_registro s
    left join cliente c on c.codigo = s.cliente_codigo
    left join v_staging_chaves k
        on k.lote_id = s.lote_id and k.entidade = 'CLIENTE'
        and k.codigo = s.cliente_codigo and k.quantidade = 1
    where s.entidade = 'CONTRATO'
      and regexp_like(s.cliente_codigo, '^[A-Z0-9][A-Z0-9_-]{0,29}$', 'c')
      and c.id is null
      and (k.linha is null or exists (
          select 1 from v_erros_locais e
          where e.lote_id = k.lote_id and e.entidade = k.entidade and e.linha = k.linha
      ))
)
select r.lote_id, r.entidade, r.linha, r.campo, r.codigo_erro codigo,
       case r.codigo_erro
           when 'FORMATO_CSV' then s.erro_csv
           when 'OBRIGATORIO' then 'Campo obrigatorio ausente'
           when 'FORMATO' then 'Formato do campo invalido'
           when 'TAMANHO' then 'Campo excede o tamanho permitido'
           when 'DATA_INVALIDA' then 'Use uma data existente desde 1900 no formato AAAA-MM-DD'
           when 'PERIODO' then 'Fim anterior ao inicio'
           when 'VALOR_INVALIDO' then 'Use valor positivo com ponto decimal e ate duas casas'
           when 'DUPLICADO' then 'Codigo repetido para esta entidade no lote'
           when 'ORFAO' then 'Cliente ausente no destino e sem registro valido no lote'
       end motivo
from regras r
join staging_registro s on s.lote_id = r.lote_id and s.entidade = r.entidade and s.linha = r.linha;

create or replace view v_estado_staging as
select s.lote_id, s.entidade, s.linha, s.codigo,
       case when e.linha is null then 0 else 1 end tem_erro
from staging_registro s
left join (
    select lote_id, entidade, linha from erro_validacao group by lote_id, entidade, linha
) e on e.lote_id = s.lote_id and e.entidade = s.entidade and e.linha = s.linha;

create or replace procedure validar_lote (
    p_lote_id in number, p_validos out number, p_invalidos out number
) as
    v_estado lote.estado%type;
begin
    select estado into v_estado from lote where id = p_lote_id for update nowait;
    if v_estado not in ('IMPORTADO', 'INVALIDO', 'VALIDADO') then
        raise_application_error(-20002, 'Estado nao permite validacao');
    end if;

    delete from erro_validacao where lote_id = p_lote_id;
    insert into erro_validacao (lote_id, entidade, linha, campo, codigo, motivo)
        select lote_id, entidade, linha, campo, codigo, motivo
        from v_erros_lote where lote_id = p_lote_id;

    select count(*) - coalesce(sum(tem_erro), 0), coalesce(sum(tem_erro), 0)
    into p_validos, p_invalidos
    from v_estado_staging
    where lote_id = p_lote_id;

    update lote
       set estado = case when p_invalidos = 0 then 'VALIDADO' else 'INVALIDO' end,
           motor = 'PLSQL', validos = p_validos, invalidos = p_invalidos,
           validado_em = sys_extract_utc(systimestamp)
     where id = p_lote_id;
end;
/
