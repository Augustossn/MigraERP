whenever oserror exit failure rollback
whenever sqlerror exit sql.sqlcode rollback
set serveroutput on feedback off

declare
    v_lote number;
begin
    insert into lote (estado) values ('IMPORTADO') returning id into v_lote;

    insert into staging_registro (lote_id, entidade, linha, codigo, nome, email)
        select v_lote, 'CLIENTE', n + 1,
               'PERF-C-' || case when mod(n,1000) = 0 then n - 1 else n end,
               'Cliente ficticio ' || n, 'cliente' || n || '@example.test'
        from (select level n from dual connect by level <= 50000);

    insert into staging_registro
        (lote_id, entidade, linha, codigo, cliente_codigo, inicio_txt, fim_txt, valor_txt)
        select v_lote, 'CONTRATO', n + 1, 'PERF-T-' || n,
               case when mod(n,500) = 0 then 'PERF-AUSENTE' else 'PERF-C-' || n end,
               case when mod(n,100) = 0 then '2026-02-30' else '2026-01-01' end,
               '2026-12-31', '1500.55'
        from (select level n from dual connect by level <= 50000);

    commit;
    dbms_output.put_line('LOTE_PERF=' || v_lote);
end;
/
exit success
