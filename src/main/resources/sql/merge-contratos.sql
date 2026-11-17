merge into contrato t
using (
    select s.codigo, c.id cliente_id,
           to_date(s.inicio_txt, 'FXYYYY-MM-DD') inicio,
           to_date(s.fim_txt, 'FXYYYY-MM-DD') fim,
           to_number(s.valor_txt, '9999999990D99', 'NLS_NUMERIC_CHARACTERS=''.,''') valor
    from staging_registro s
    join cliente c on c.codigo = s.cliente_codigo
    where s.lote_id = ? and s.entidade = 'CONTRATO'
) s
on (t.codigo = s.codigo)
when matched then update set t.cliente_id = s.cliente_id, t.inicio = s.inicio,
                             t.fim = s.fim, t.valor = s.valor
when not matched then insert (codigo, cliente_id, inicio, fim, valor)
                      values (s.codigo, s.cliente_id, s.inicio, s.fim, s.valor)
