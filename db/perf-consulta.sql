explain plan set statement_id = '&2' for
select count(*) from staging_registro
where lote_id = &1 and entidade = 'CLIENTE' and codigo = 'PERF-C-47111';

select plan_table_output from table(dbms_xplan.display('PLAN_TABLE', '&2', 'TYPICAL +PREDICATE'));

prompt Execucao real e aquecimento (tempo total no SQL*Plus)
set timing on
select count(*) encontrados from staging_registro
where lote_id = &1 and entidade = 'CLIENTE' and codigo = 'PERF-C-47111';

select count(*) encontrados from staging_registro
where lote_id = &1 and entidade = 'CLIENTE' and codigo = 'PERF-C-47111';

select count(*) encontrados from staging_registro
where lote_id = &1 and entidade = 'CLIENTE' and codigo = 'PERF-C-47111';
set timing off
