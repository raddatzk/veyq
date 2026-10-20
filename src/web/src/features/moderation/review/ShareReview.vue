<script setup lang="ts">
// The share viewer's moderation mode (`/s/<caseId>#moderation`, uc.moderation.review-a-case step 3; ADR 0093 § 3, ADR
// 0099 § 6): on the reported tenant's host, the moderation gate signs the moderator in and unlocks only the moderation
// grants — no member shell, no scope grant, the Master Key and the identity key dropped before anything renders — and the
// review renders behind it. The page never offers a trusted device (ADR 0099 § 6).
import ModerationGate from '../ModerationGate.vue'
import ReviewBody from './ReviewBody.vue'

defineProps<{ caseId: string }>()
</script>

<template>
  <div class="share-view moderation-review-page">
    <ModerationGate wide review>
      <ReviewBody :case-id="caseId" />
    </ModerationGate>
  </div>
</template>
