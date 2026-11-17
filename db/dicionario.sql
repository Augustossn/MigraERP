set linesize 180 pagesize 100
column table_name format a20
column constraint_name format a25
column r_constraint_name format a24
column column_name format a20
column data_type format a16
column search_condition_vc format a65 word_wrapped

select sys_context('USERENV','CON_NAME') pdb, user schema_atual from dual;

select table_name from user_tables order by table_name;

select table_name, column_name, data_type, data_precision, data_scale, char_length,
       char_used, nullable, identity_column
from user_tab_columns
where table_name in ('LOTE', 'CLIENTE', 'CONTRATO', 'STAGING_REGISTRO', 'ERRO_VALIDACAO')
order by table_name, column_id;

select c.table_name, c.constraint_name, c.constraint_type tipo, cc.column_name,
       c.r_constraint_name, c.status, c.search_condition_vc
from user_constraints c
left join user_cons_columns cc on cc.constraint_name = c.constraint_name
where c.table_name in ('LOTE', 'CLIENTE', 'CONTRATO', 'STAGING_REGISTRO', 'ERRO_VALIDACAO')
order by c.table_name, c.constraint_name, cc.position;

select table_name, index_name, column_name, column_position
from user_ind_columns
where table_name in ('CLIENTE', 'CONTRATO', 'STAGING_REGISTRO', 'ERRO_VALIDACAO')
order by table_name, index_name, column_position;

select name, type, line, position, text from user_errors order by name, sequence;
exit
