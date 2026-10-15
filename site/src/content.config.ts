import { defineCollection } from 'astro:content';
import { z } from 'astro/zod';
import { docsLoader } from '@astrojs/starlight/loaders';
import { docsSchema } from '@astrojs/starlight/schema';

export const collections = {
  docs: defineCollection({
    loader: docsLoader(),
    schema: docsSchema({
      extend: z.object({
        // The docs under docs/ a page speaks for, as repo-relative paths (ADR 0087 §4). Each declared doc links
        // the page back, so a change to a doc shows which published page to re-read.
        sources: z.array(z.string()).optional(),
      }),
    }),
  }),
};
