-- V5__embedding_usage_single_name.sql
-- Embedding usage is recorded under the single name 'embed' instead of 'embed:<model>'
-- (TrackingEmbeddingModel.PROVIDER_NAME). The embedding model is fixed for a deployment — changing
-- EMBED_MODEL means dropping the vectors and re-indexing everything — so a per-model split only
-- produced an 'embed:<old-model>' orphan card after such a change. Rows already recorded per model
-- are summed into 'embed' per day: token totals and call counts are kept, only the model split goes.

INSERT INTO llm_usage (provider_name, usage_date, input_tokens, output_tokens, call_count)
SELECT 'embed', usage_date, SUM(input_tokens), SUM(output_tokens), SUM(call_count)
FROM llm_usage
WHERE provider_name LIKE 'embed:%'
GROUP BY usage_date
ON CONFLICT (provider_name, usage_date) DO UPDATE SET
    input_tokens  = input_tokens  + excluded.input_tokens,
    output_tokens = output_tokens + excluded.output_tokens,
    call_count    = call_count    + excluded.call_count;

DELETE FROM llm_usage WHERE provider_name LIKE 'embed:%';
