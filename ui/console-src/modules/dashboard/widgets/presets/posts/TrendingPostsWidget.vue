<script lang="ts" setup>
import WidgetCard from "@console/modules/dashboard/components/WidgetCard.vue";
import type { ListedPost } from "@halo-dev/api-client";
import { consoleApiClient } from "@halo-dev/api-client";
import { VButton, VEmpty, VLoading } from "@halo-dev/components";
import { useQuery } from "@tanstack/vue-query";
import { computed, toRefs } from "vue";
import { postLabels } from "@/constants/labels";

const props = defineProps<{
  config: {
    top_n: number;
  };
}>();

const { config } = toRefs(props);

const topN = computed(() => config.value.top_n || 10);

const { data, isLoading, isFetching, refetch } = useQuery<ListedPost[]>({
  queryKey: computed(() => ["widget-trending-posts", topN.value]),
  queryFn: async () => {
    const { data } = await consoleApiClient.content.post.listPosts({
      labelSelector: [
        `${postLabels.DELETED}=false`,
        `${postLabels.PUBLISHED}=true`,
      ],
      page: 1,
      size: topN.value,
      sort: ["stats.visit,desc"],
    });
    return data.items;
  },
});

const maxVisit = computed(() => {
  if (!data.value?.length) return 1;
  return Math.max(...data.value.map((p) => p.stats?.visit || 0), 1);
});
</script>
<template>
  <WidgetCard
    :body-class="['!overflow-auto']"
    :title="$t('core.dashboard.widgets.presets.trending_posts.title')"
  >
    <VLoading v-if="isLoading" />
    <VEmpty
      v-else-if="!data?.length"
      :title="$t('core.dashboard.widgets.presets.trending_posts.empty.title')"
    >
      <template #actions>
        <VButton :loading="isFetching" @click="refetch">
          {{ $t("core.common.buttons.refresh") }}
        </VButton>
      </template>
    </VEmpty>
    <div v-else class="flex h-full flex-col gap-2 overflow-auto px-4 py-3">
      <div
        v-for="(item, index) in data"
        :key="item.post.metadata.name"
        class="flex items-center gap-2"
      >
        <span class="w-5 shrink-0 text-right text-xs text-gray-400">
          {{ index + 1 }}
        </span>
        <div class="min-w-0 flex-1">
          <div class="mb-0.5 flex items-center justify-between gap-1">
            <router-link
              :to="{
                name: 'PostEditor',
                query: { name: item.post.metadata.name },
              }"
              class="truncate text-sm text-gray-800 hover:text-blue-600 hover:underline"
            >
              {{ item.post.spec.title }}
            </router-link>
            <span class="shrink-0 text-xs text-gray-500">
              {{
                $t(
                  "core.dashboard.widgets.presets.trending_posts.visits_count",
                  { count: item.stats?.visit || 0 }
                )
              }}
            </span>
          </div>
          <div class="h-1.5 w-full overflow-hidden rounded-full bg-gray-100">
            <div
              class="h-full rounded-full bg-blue-500 transition-all duration-500"
              :style="{
                width: `${(((item.stats?.visit || 0) / maxVisit) * 100).toFixed(1)}%`,
              }"
            ></div>
          </div>
        </div>
      </div>
    </div>
  </WidgetCard>
</template>
