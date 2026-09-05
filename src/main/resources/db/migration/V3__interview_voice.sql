-- Кандидат выбирает голос интервьюера перед началом.
-- null означает «голос по умолчанию из конфига».
alter table interview add column tts_voice varchar(40);
