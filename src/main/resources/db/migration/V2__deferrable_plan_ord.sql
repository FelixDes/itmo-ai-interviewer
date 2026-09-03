-- Вставка уточняющего вопроса сдвигает хвост плана одним UPDATE ord = ord + 1.
-- Обычный unique проверяется построчно и падает на пересечении промежуточных
-- значений, поэтому переводим его в отложенный: проверка на коммите, когда
-- порядок уже консистентен.

alter table interview_question
    drop constraint interview_question_interview_id_ord_key;

alter table interview_question
    add constraint interview_question_interview_id_ord_key
        unique (interview_id, ord) deferrable initially deferred;
