merge into cliente t
using (
    select codigo, nome, email
    from staging_registro
    where lote_id = ? and entidade = 'CLIENTE'
) s
on (t.codigo = s.codigo)
when matched then update set t.nome = s.nome, t.email = s.email
when not matched then insert (codigo, nome, email) values (s.codigo, s.nome, s.email)
