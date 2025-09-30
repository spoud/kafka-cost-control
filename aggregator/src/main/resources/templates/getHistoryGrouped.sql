{#if groupByHour}
    SELECT subquery.context, subquery.start_time, SUM(subquery.value) as sum
    FROM (
         SELECT json_value(context, ?) as context, start_time, value
         FROM aggregated_data
         WHERE start_time >= ? and end_time <= ?
           {#if metricNames.size > 0}
           AND initial_metric_name IN (
             {#each metricNames}
                ?{#if it_hasNext}, {/if}
             {/each}
           )
           {/if}
        ) as subquery
    GROUP BY context, start_time
    ORDER BY start_time
{#else}
    SELECT subquery.context, SUM(subquery.value) as sum
    FROM (
         SELECT json_value(context, ?) as context, start_time, value
         FROM aggregated_data
         WHERE start_time >= ? and end_time <= ?
           {#if metricNames.size > 0}
           AND initial_metric_name IN (
             {#each metricNames}
                ?{#if it_hasNext}, {/if}
             {/each}
           )
           {/if}
         ) as subquery
    GROUP BY context
{/if}
