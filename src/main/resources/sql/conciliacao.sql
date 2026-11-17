with lote_consultado as (
    select id, estado from lote where id = ?
), tipos as (
    select 'CLIENTE' entidade from dual union all select 'CONTRATO' from dual
), contagem as (
    select s.entidade, count(*) importados, sum(s.tem_erro) invalidos,
           sum(case when c.id is not null or t.id is not null then 1 else 0 end) chaves_no_destino
    from v_estado_staging s
    left join cliente c on s.entidade = 'CLIENTE' and c.codigo = s.codigo
    left join contrato t on s.entidade = 'CONTRATO' and t.codigo = s.codigo
    join lote_consultado l on l.id = s.lote_id
    group by s.entidade
)
select tipos.entidade, coalesce(c.importados, 0) importados,
       case when l.estado <> 'IMPORTADO' then coalesce(c.importados - c.invalidos, 0) end validos,
       case when l.estado <> 'IMPORTADO' then coalesce(c.invalidos, 0) end invalidos,
       coalesce(c.chaves_no_destino, 0) chaves_no_destino
from lote_consultado l cross join tipos
left join contagem c on c.entidade = tipos.entidade
order by tipos.entidade
