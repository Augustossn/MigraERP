whenever oserror exit failure rollback
whenever sqlerror exit sql.sqlcode rollback
set define off
@@schema.sql
@@validation.sql
declare
    invalidos number;
begin
    select count(*) into invalidos from user_objects where status = 'INVALID';
    if invalidos > 0 then
        raise_application_error(-20001, 'Objetos invalidos. Consulte USER_ERRORS.');
    end if;
end;
/
exit success
