import Image from "next/image";
import {
  BookOpen,
  ClipboardCheck,
  MessageCircle,
  UserRound,
  Wind,
} from "lucide-react";

import { Container } from "@/components/layout/container";
import { SectionHeading } from "@/components/shared/section-heading";
import { FeatureCard } from "@/components/shared/feature-card";

const tools = [
  {
    icon: MessageCircle,
    tone: "blue" as const,
    title: "Talk to Neuro",
    description:
      "A supportive, judgment-free space to talk about what's on your mind.",
    href: "/ai-companion",
    ctaLabel: "Start a conversation",
    image: "/images/nuro.png",
    alt: "A chat message from Neuro asking how you're feeling, in a cozy sunlit room",
  },
  {
    icon: ClipboardCheck,
    tone: "green" as const,
    title: "Assessments",
    description:
      "Evidence-based tools to help you understand your mental wellbeing.",
    href: "/assessment",
    ctaLabel: "Take an assessment",
    image: "/images/assessments.png",
    alt: "An open reflection journal with a pen, resting on a wooden desk",
  },
  {
    icon: Wind,
    tone: "purple" as const,
    title: "Relaxation Hub",
    description:
      "Guided meditations, breathing exercises and sleep sounds to help you unwind.",
    href: "/relaxation",
    ctaLabel: "Find your calm",
    image: "/images/relaxation.png",
    alt: "A calm mountain lake with a wooden dock at sunrise",
  },
  {
    icon: BookOpen,
    tone: "amber" as const,
    title: "Wellness Resources",
    description:
      "Articles to help you learn and grow at your own pace.",
    href: "/resources",
    ctaLabel: "Explore resources",
    image: "/images/wellness.png",
    alt: "A stack of books beside a potted plant in a sunlit reading corner",
  },
  {
    icon: UserRound,
    tone: "rose" as const,
    title: "Find a Licensed Professional",
    description:
      "Connect with licensed mental health professionals (psychologists, psychiatrists and registered counsellors) when you need extra support.",
    href: "/professionals",
    ctaLabel: "Find support",
    image: "/images/professionals.png",
    alt: "A calm, sunlit room with an armchair looking out over a mountain lake",
  },
];

function ExploreToolsSection() {
  return (
    <section id="tools" className="scroll-mt-20 py-16 sm:py-24">
      <Container className="flex flex-col gap-10">
        <SectionHeading
          eyebrow="Explore NeuroNest"
          title="Tools for your mental wellbeing"
          description="Support, guidance and resources — all in one peaceful space."
        />

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
          {tools.map((tool) => (
            <FeatureCard
              key={tool.title}
              icon={tool.icon}
              tone={tool.tone}
              title={tool.title}
              description={tool.description}
              href={tool.href}
              ctaLabel={tool.ctaLabel}
              mediaClassName="aspect-[16/9]"
              media={
                <Image
                  src={tool.image}
                  alt={tool.alt}
                  fill
                  sizes="(min-width: 1280px) 20vw, (min-width: 1024px) 33vw, (min-width: 640px) 50vw, 100vw"
                  className="object-cover"
                />
              }
            />
          ))}
        </div>
      </Container>
    </section>
  );
}

export { ExploreToolsSection };
