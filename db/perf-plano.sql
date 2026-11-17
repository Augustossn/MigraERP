whenever oserror exit failure rollback
whenever sqlerror exit sql.sqlcode rollback
set linesize 180 pagesize 100 verify off

delete from plan_table where statement_id in ('MIGRA_ANTES','MIGRA_DEPOIS');
-- Limpa o índice experimental se uma execução anterior foi interrompida.
drop index if exists ix_staging_bench;
exec dbms_stats.gather_table_stats(user, 'STAGING_REGISTRO', cascade => true);

prompt === ANTES: sem o indice experimental ===
@@perf-consulta.sql &1 MIGRA_ANTES

create index ix_staging_bench on staging_registro(lote_id, entidade, codigo);
exec dbms_stats.gather_index_stats(user, 'IX_STAGING_BENCH');

prompt === DEPOIS: com o indice experimental ===
@@perf-consulta.sql &1 MIGRA_DEPOIS

drop index ix_staging_bench;
delete from plan_table where statement_id in ('MIGRA_ANTES','MIGRA_DEPOIS');
commit;
exit success
