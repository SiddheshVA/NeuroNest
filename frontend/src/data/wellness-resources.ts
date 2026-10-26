/**
 * Wellness Resources content: articles.
 *
 * Content will eventually come from the backend (an admin-managed ResourceContent table); until
 * then this file is the single source. Components only read through the getters at the bottom, so
 * switching to an API call means changing this file, not the UI.
 */

export const articleCategories = [
  "Understanding Common Concerns",
  "Student-Focused",
  "Everyday Self-Care",
  "Support & Professional Help",
] as const;

export type ArticleCategory = (typeof articleCategories)[number];

/** Assessment codes an article can point readers to (see App.tsx routes). */
export type RelatedAssessment = "WHO5" | "GAD7" | "PHQ9" | "PSS10" | "SCI";

export interface ResourceSource {
  title: string;
  url: string;
}

/** A bold lead-in followed by text, e.g. a numbered step or a listed cause. */
export interface TitledText {
  title: string;
  text: string;
}

export type ReviewStatus = "pending" | "reviewed";

export interface Article {
  id: string;
  slug: string;
  title: string;
  category: ArticleCategory;
  summary: string;
  readingTimeMinutes: number;
  tags: string[];
  relatedAssessment: RelatedAssessment | null;
  /** Also show the crisis box right under the title, before the intro. Defaults to false. */
  showCrisisBoxAtTop?: boolean;
  language: string;
  reviewStatus: ReviewStatus;
  sections: {
    intro: string;
    /** Paragraphs. */
    whatItIs: string[];
    /** Bullet list + closing line. */
    signs: { items: string[]; note: string };
    whyItHappens: { paragraphs: string[]; causes: TitledText[]; closing: string };
    /** Numbered steps (title in bold) + a highlighted "things to avoid" note. */
    whatYouCanDo: { steps: TitledText[]; thingsToAvoid: string };
    whenToSeekHelp: { intro: string; points: string[]; closing: string };
  };
  sources: ResourceSource[];
  /** ISO date, e.g. "2026-01-15"; null until reviewed. */
  lastReviewedDate: string | null;
  reviewedBy: string | null;
}

// Example article shape:
// {
//   id: "article-000",
//   slug: "understanding-exam-anxiety",
//   title: "Understanding Exam Anxiety",
//   category: "Student-Focused",
//   summary: "Why exams can feel overwhelming, and what helps.",
//   readingTimeMinutes: 6,
//   tags: ["anxiety", "exams", "students"],
//   relatedAssessment: "GAD7", // or null (hides the assessment button)
//   showCrisisBoxAtTop: false, // optional; true for crisis-focused articles
//   language: "en",
//   reviewStatus: "pending", // or "reviewed"
//   sections: {
//     intro: "...",
//     whatItIs: ["paragraph", "paragraph"],
//     signs: { items: ["...", "..."], note: "..." },
//     whyItHappens: {
//       paragraphs: ["..."],
//       causes: [{ title: "Studies", text: "..." }],
//       closing: "...",
//     },
//     whatYouCanDo: {
//       steps: [{ title: "Name what's stressing you.", text: "..." }],
//       thingsToAvoid: "...",
//     },
//     whenToSeekHelp: { intro: "...", points: ["...", "..."], closing: "..." },
//   },
//   sources: [{ title: "...", url: "https://..." }],
//   lastReviewedDate: null, // ISO date once reviewed, e.g. "2026-01-15"
//   reviewedBy: null, // e.g. "Dr. ..., Clinical Psychologist" once reviewed
// }
export const articles: Article[] = [
  {
    id: "article-001",
    slug: "understanding-stress",
    title: "Understanding Stress: Causes, Signs, and Healthy Ways to Cope",
    category: "Understanding Common Concerns",
    summary: "What stress is, how to spot it, and simple things you can do to feel more in control.",
    readingTimeMinutes: 5,
    tags: ["stress", "exams", "work", "coping", "sleep"],
    relatedAssessment: "PSS10",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "Board exams next month. A deadline at work. Arguments at home. Bills piling up. If you've felt tense, tired or \"too full\" lately, you're not alone. This article explains what stress is, how to spot it, and simple things you can do to feel more in control.",

      whatItIs: [
        "Stress is the feeling of worry or mental tension you get when life puts pressure on you. It's your body's natural way of reacting to a challenge or threat. Everyone experiences stress to some degree, and how we respond to it makes a big difference to our overall wellbeing.",
        "Not all stress is bad. A little stress can help us get through daily tasks, but too much can lead to physical and mental health problems. A small push of stress before an exam can help you focus. Weeks of constant pressure with no break is when it starts to hurt.",
      ],

      signs: {
        items: [
          "Feeling worried, tense or \"on edge\" most of the time",
          "Getting irritated or angry more easily than usual",
          "Trouble concentrating, or your mind going blank",
          "Finding it hard to relax, even when you have free time",
          "Sleeping too little, too much, or waking up tired",
          "Headaches, a tight chest, stomach problems or body aches",
          "Eating more or less than usual",
          "Avoiding people, tasks or responsibilities",
          "Using more tea, coffee, cigarettes or alcohol to \"cope\"",
        ],
        note: "Stress affects both the mind and the body, and the signs can be different for every person.",
      },

      whyItHappens: {
        paragraphs: [
          "When your brain senses pressure, your body gets ready to \"fight or run\": your heart beats faster, your muscles tighten, and your mind goes on high alert. That's useful for a short burst. The problem starts when the pressure never switches off.",
        ],
        causes: [
          { title: "Studies", text: "exams, results, entrance tests, comparison with classmates" },
          { title: "Work", text: "conflict with colleagues, a very demanding or insecure job, targets and long hours" },
          { title: "Family and relationships", text: "expectations from parents, conflict at home, marriage pressure, loneliness" },
          { title: "Money", text: "fees, EMIs, job uncertainty" },
          { title: "Big life changes", text: "moving to a new city, a new job, a new college, even happy events like a wedding" },
        ],
        closing: "Often it's not one big thing but many small things adding up. Think of it like a bucket slowly filling with water until it overflows.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Name what's stressing you.", text: "Write down everything on your mind. Seeing it on paper makes it feel smaller and shows you what you can and can't control." },
          { title: "Break big tasks into small steps.", text: "\"Finish the syllabus\" is overwhelming. \"Revise one chapter today\" is doable." },
          { title: "Protect your sleep.", text: "Sleep helps your body repair and relax, and can help undo the effects of stress. Try to sleep and wake at the same time each day." },
          { title: "Move your body.", text: "Regular daily exercise can help reduce stress. A 20-minute walk, yoga or a quick game of cricket all count." },
          { title: "Take short breathing breaks.", text: "Breathe in slowly for 4 counts, hold for 4, breathe out for 4. Repeat a few times. You can also try the breathing exercises in our Relaxation Hub." },
          { title: "Limit news and social media.", text: "Too much time on news and social media can increase stress. Set a time limit if scrolling leaves you feeling worse." },
          { title: "Talk to someone you trust.", text: "A friend, sibling, parent or teacher. Saying it out loud often helps more than you expect." },
        ],
        thingsToAvoid: "Avoid relying on alcohol, smoking or other substances to cope. Long-term stress can make people use them more, and they make stress worse over time.",
      },

      whenToSeekHelp: {
        intro: "Stress is normal, but it's time to get support if:",
        points: [
          "It has lasted for several weeks and isn't getting better",
          "It's affecting your studies, work, sleep or relationships",
          "You feel hopeless, constantly low, or unable to cope",
          "You're using alcohol or other substances to get through the day",
        ],
        closing: "Not sure how stressed you are? Take our short stress check to find out. If you'd like to talk to someone, you can find a qualified counselor, psychologist or psychiatrist through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "World Health Organization — Stress: Questions and Answers", url: "https://www.who.int/news-room/questions-and-answers/item/stress" },
      { title: "World Health Organization — Doing What Matters in Times of Stress: An Illustrated Guide", url: "https://www.who.int/publications/i/item/9789240003927" },
      { title: "NHS — Tips to reduce stress", url: "https://www.nhs.uk/mental-health/self-help/guides-tools-and-activities/tips-to-reduce-stress/" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-002",
    slug: "understanding-burnout",
    title: "What Is Burnout? Recognizing the Signs and Taking Action",
    category: "Understanding Common Concerns",
    summary: "How burnout is different from everyday tiredness, the warning signs to watch for, and practical steps to recover.",
    readingTimeMinutes: 5,
    tags: ["burnout", "work", "stress", "exhaustion", "students"],
    relatedAssessment: "PSS10",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "You sleep, but wake up tired. Work that once felt meaningful now feels pointless. You keep going, but you're running on empty. If this sounds familiar, you may be heading towards burnout. This article explains what burnout is, how to recognise it early, and what you can do about it.",

      whatItIs: [
        "Burnout is what can happen when stress at work goes on for a long time and isn't managed. The World Health Organization describes it as an \"occupational phenomenon\" — a work-related problem, not a medical illness. But it is real, and it can seriously affect your health and happiness.",
        "Burnout is different from a busy week or one tough month. Normal tiredness gets better after rest or a holiday. Burnout often doesn't — you come back from a break and feel drained again within days.",
        "Students often use the word \"burnout\" too, for the exhaustion that comes from months of studying, coaching classes and exam pressure. Strictly speaking, the WHO term is about work, but the feelings can be very similar — and the steps in this article can help either way.",
      ],

      signs: {
        items: [
          "Feeling exhausted most of the time, even after sleeping or resting",
          "Dreading work every morning, or a sinking feeling on Sunday evenings",
          "Feeling detached, numb or cynical about your job — \"what's the point?\"",
          "Getting less done, making more mistakes, or struggling to focus",
          "Feeling that nothing you do is good enough or makes a difference",
          "Getting irritated with colleagues, family or friends more easily",
          "Frequent headaches, body aches, stomach problems or falling sick often",
          "Pulling away from people and things you used to enjoy",
        ],
        note: "WHO describes burnout through three main signs: feeling exhausted, feeling mentally distant or negative about your work, and feeling less effective at it. You don't need every sign above for burnout to be worth taking seriously.",
      },

      whyItHappens: {
        paragraphs: [
          "Burnout builds up slowly. Usually it's not one bad day, but months of pressure without enough recovery. It often happens to people who care a lot about doing well.",
        ],
        causes: [
          { title: "Too much work", text: "long hours, unrealistic targets, weekend work, always being \"on call\" on WhatsApp or email" },
          { title: "Little control", text: "having no say in your tasks, deadlines or how you do your work" },
          { title: "Lack of recognition", text: "working hard but feeling unnoticed, unrewarded or underpaid" },
          { title: "Poor work environment", text: "conflict with a manager or colleagues, unfair treatment, or feeling unsupported" },
          { title: "No boundary between work and home", text: "work-from-home blurring into evenings, no real time off" },
          { title: "Job insecurity", text: "fear of layoffs, appraisals or not meeting expectations" },
        ],
        closing: "When these pressures continue without rest, your energy keeps draining faster than it can refill — until there's nothing left in the tank.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Notice it early.", text: "Burnout is easier to recover from when you catch it early. If several of the signs above sound like you, take them seriously rather than pushing harder." },
          { title: "Set clear work boundaries.", text: "Pick a time to stop working each day and stick to it. Turn off work notifications after hours where you can." },
          { title: "Take real breaks.", text: "Short breaks during the day and proper days off — without checking email — help your mind recover." },
          { title: "Talk to your manager or HR.", text: "If workload is the problem, say so calmly and specifically: \"I'm handling X, Y and Z. Which should I prioritise?\" Many workplaces can adjust more than you expect." },
          { title: "Look after the basics.", text: "Regular sleep, simple meals and some daily movement give your body the energy it needs to cope." },
          { title: "Reconnect with what you enjoy.", text: "Make time for friends, family, hobbies or anything outside work that makes you feel like yourself." },
          { title: "Lean on people you trust.", text: "Talking to a friend, partner or family member helps you feel less alone and can give you a fresh view of the situation." },
        ],
        thingsToAvoid: "Avoid \"pushing through\" by working even longer hours, skipping sleep, or relying on alcohol, smoking or too much caffeine. These can feel like they help in the short term, but they make burnout worse.",
      },

      whenToSeekHelp: {
        intro: "It's a good idea to get support if:",
        points: [
          "You've felt exhausted and detached for weeks, and rest isn't helping",
          "It's affecting your health, sleep, relationships or ability to do your job",
          "You feel low, hopeless or worthless most of the time",
          "You're thinking about quitting everything or feel you can't go on",
        ],
        closing: "Burnout can overlap with anxiety and depression, so it's worth talking to a professional if things feel heavy. You can take our short stress check, or find a qualified counselor, psychologist or psychiatrist through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "World Health Organization — Burn-out an \"occupational phenomenon\": International Classification of Diseases", url: "https://www.who.int/news/item/28-05-2019-burn-out-an-occupational-phenomenon-international-classification-of-diseases" },
      { title: "World Health Organization — Stress: Questions and Answers", url: "https://www.who.int/news-room/questions-and-answers/item/stress" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-003",
    slug: "loneliness-and-social-connection",
    title: "Dealing With Loneliness and Building Social Connection",
    category: "Understanding Common Concerns",
    summary: "Why loneliness can happen even around people, how it affects you, and small steps to feel more connected.",
    readingTimeMinutes: 5,
    tags: ["loneliness", "friendship", "connection", "new city", "hostel", "relationships"],
    relatedAssessment: "WHO5",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "You moved to a new city for college or work. Your phone is full of contacts, but no one to really talk to. Or you live with family, yet feel no one truly understands you. Loneliness is more common than most people admit — and it can be eased. This article explains what loneliness is, why it matters, and small steps to feel more connected.",

      whatItIs: [
        "Loneliness is the painful feeling that your relationships aren't giving you the closeness or support you need. It's about how you feel, not how many people are around you. You can feel lonely in a crowded hostel, an office, or even at a family dinner.",
        "It's different from being alone. Some people enjoy time by themselves and don't feel lonely at all. \"Social isolation\" means actually having very little contact with others, while loneliness is the feeling of being disconnected. You can have one without the other.",
        "You're not the only one. The World Health Organization reports that about one in six people around the world feel lonely — and young people are among the most affected.",
      ],

      signs: {
        items: [
          "Feeling empty, left out, or like no one really \"gets\" you",
          "Having people around but no one you can open up to",
          "Spending a lot of time scrolling, but feeling worse afterwards",
          "Feeling nervous or awkward about reaching out to others",
          "Turning down plans, then feeling more alone",
          "Feeling sad or restless, especially in the evenings or on weekends",
          "Trouble sleeping or low energy",
          "Thinking others have closer friendships than you do",
        ],
        note: "Feeling lonely now and then is a normal part of life. It's worth paying attention to when it lasts for weeks or starts to affect your mood and daily life.",
      },

      whyItHappens: {
        paragraphs: [
          "Humans are wired to need connection. When that need isn't met, the mind treats it a bit like hunger — a signal that something important is missing. Loneliness often starts with a change in life, and can grow if we start pulling away.",
        ],
        causes: [
          { title: "Moving away", text: "a new city for college, a job or marriage, living in a hostel or PG far from home" },
          { title: "Life changes", text: "friends moving away, finishing college, starting a new job, a breakup or loss" },
          { title: "Busy schedules", text: "long work hours, coaching classes or shifts that leave no time for people" },
          { title: "Feeling different", text: "not fitting in because of language, background, interests or identity" },
          { title: "Too much screen time", text: "online contact replacing face-to-face time, and comparing your life to others' highlight reels" },
          { title: "Low mood or anxiety", text: "making it harder to reach out, which then increases loneliness" },
        ],
        closing: "Loneliness can become a cycle: feeling lonely makes us withdraw, and withdrawing makes us lonelier. The good news is that small steps can break this cycle.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Start small.", text: "You don't need a big friend group. Send one message to an old friend, a cousin or a classmate today — even just \"Saw this and thought of you.\"" },
          { title: "Choose quality over quantity.", text: "One or two people you can be honest with matter more than hundreds of followers." },
          { title: "Make regular plans.", text: "A weekly call home, a Sunday chai with a roommate, or a regular walk with a colleague builds connection over time." },
          { title: "Join something with a shared interest.", text: "A college club, sports group, gym class, library, volunteer group, religious or cultural group — shared activities make talking easier." },
          { title: "Say yes more often.", text: "When someone invites you, try going even if you feel unsure. Connection grows slowly through small moments." },
          { title: "Use your phone to connect, not just scroll.", text: "Voice and video calls feel closer than likes and comments. Notice if social media leaves you feeling worse, and cut back if it does." },
          { title: "Be kind to yourself.", text: "Loneliness doesn't mean something is wrong with you. Many people feel the same way — they just don't talk about it." },
        ],
        thingsToAvoid: "Avoid shutting yourself away, or trying to fill the empty feeling with endless scrolling, binge-watching, alcohol or other substances. These can make loneliness deeper over time.",
      },

      whenToSeekHelp: {
        intro: "It's a good idea to talk to someone if:",
        points: [
          "You've felt lonely or disconnected most days for several weeks",
          "It's affecting your sleep, studies, work or appetite",
          "You feel sad, empty or hopeless most of the time",
          "You feel like no one would notice or care if you weren't around",
        ],
        closing: "Long-term loneliness can affect both mental and physical health, and it's okay to ask for support. You can take our short wellbeing check, or talk to a qualified counselor or psychologist through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "World Health Organization — Commission on Social Connection", url: "https://www.who.int/groups/commission-on-social-connection" },
      { title: "World Health Organization — Social isolation and loneliness have serious impacts", url: "https://www.who.int/multi-media/details/social-isolation-and-loneliness-have-serious-impacts" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-004",
    slug: "managing-exam-stress",
    title: "Managing Exam Stress and Academic Pressure",
    category: "Student-Focused",
    summary: "Why exams feel so heavy, how to spot when pressure is becoming too much, and practical ways to study and stay well.",
    readingTimeMinutes: 6,
    tags: ["exams", "students", "board exams", "JEE", "NEET", "results", "study", "stress"],
    relatedAssessment: "PSS10",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "Board exams, JEE, NEET, CUET, semester finals — and everyone asking \"how's the preparation going?\" If exams are making you anxious, tired or overwhelmed, you're far from alone. This article explains why exam pressure feels so heavy, how to tell when it's becoming too much, and practical ways to study well while taking care of yourself.",

      whatItIs: [
        "Exam stress is the worry and pressure you feel before and during exams. Some nervousness is completely normal — it shows you care, and a little of it can even help you focus and stay motivated.",
        "Academic pressure is the bigger, longer-lasting weight behind it: high expectations from family, teachers or yourself, competition for limited seats, and the feeling that one result will decide your whole future.",
        "The problem starts when the pressure becomes so heavy that it stops you from studying, sleeping or feeling like yourself. That's when it's time to slow down and get support.",
      ],

      signs: {
        items: [
          "Constant worry about exams, results or \"what if I fail?\"",
          "Trouble concentrating, or reading the same page again and again",
          "Your mind going blank while studying or during the exam",
          "Headaches, stomach aches, nausea or a racing heart",
          "Trouble sleeping, or sleeping much more than usual",
          "Losing your appetite, or eating much more than usual",
          "Getting irritated or crying more easily, mood swings",
          "Avoiding studying altogether because it feels too overwhelming",
          "Comparing yourself to classmates and feeling you're always behind",
        ],
        note: "Exam stress affects both your mind and body. Noticing the signs early makes it easier to manage.",
      },

      whyItHappens: {
        paragraphs: [
          "In India, exams can feel like they carry enormous weight. Many students feel their marks or rank will decide their college, career, and even how their family sees them. That kind of pressure builds up over months.",
        ],
        causes: [
          { title: "High expectations", text: "from parents, relatives, teachers — or from yourself" },
          { title: "Competition", text: "limited seats, ranks, cut-offs, and constant comparison with classmates or toppers" },
          { title: "Heavy schedules", text: "school, coaching classes, self-study and tests with little time to rest" },
          { title: "Uncertainty", text: "not having a clear study plan, or not knowing what will come in the paper" },
          { title: "Living away from home", text: "hostel or PG life for coaching or college, missing family support" },
          { title: "Fear of results", text: "worrying about disappointing others or \"wasting\" a year" },
        ],
        closing: "Remember: an exam measures how you did on a few papers at one point in time. It doesn't measure your worth, your intelligence, or your whole future — there is always more than one path forward.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Make a realistic study plan.", text: "Break the syllabus into small daily targets. Start early so you're not trying to cover everything at the last minute." },
          { title: "Study in focused blocks with breaks.", text: "Try 45–50 minutes of study followed by a 10-minute break. Stand up, stretch, drink water or step outside." },
          { title: "Protect your sleep.", text: "Good sleep helps your brain remember what you studied. Avoid all-night study sessions, especially the night before an exam." },
          { title: "Eat regularly and move your body.", text: "Simple meals, enough water, and a short walk or some stretching each day help you think more clearly." },
          { title: "Talk about how you feel.", text: "Share your worries with a friend, parent, teacher or school counselor. You don't have to carry it alone." },
          { title: "Calm your body when panic rises.", text: "Breathe in slowly for 4 counts, hold for 4, breathe out for 4. If your mind goes blank in the exam, skip that question, breathe, and come back to it later." },
          { title: "Limit comparison.", text: "Stop checking how much others have studied. Focus on your own progress, one day at a time." },
        ],
        thingsToAvoid: "Avoid cramming everything the night before, skipping meals and sleep, and relying on too much caffeine or energy drinks. After each exam, try not to replay every answer — it's done, and your energy is better spent on the next one.",
      },

      whenToSeekHelp: {
        intro: "It's important to get support if:",
        points: [
          "Exam worry is constant and stops you from studying or sleeping for days at a time",
          "You feel low, hopeless or worthless most of the time",
          "You feel your life is over if you don't get the result you want",
          "You're having thoughts of hurting yourself or that you can't go on",
        ],
        closing: "No exam or result is worth your life or your health. If things feel too heavy, please reach out — to someone you trust, a school or college counselor, or a professional through Find Licensed Professional. You can also take our short stress check to understand how you're feeling.",
      },
    },
    sources: [
      { title: "UNICEF India — How to deal with anxiety before exams", url: "https://www.unicef.org/india/stories/how-deal-anxiety-exams" },
      { title: "Humber Teaching NHS Foundation Trust — Top tips for coping with exam stress", url: "https://www.humber.nhs.uk/media/qzzbqk1i/top-tips-for-coping-with-exam-stress.pdf" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-005",
    slug: "coping-with-failure-and-setbacks",
    title: "How to Cope With Failure, Setbacks, and Disappointment",
    category: "Student-Focused",
    summary: "Why failure hurts so much, how to handle the feelings that come with it, and practical steps to move forward.",
    readingTimeMinutes: 5,
    tags: ["failure", "setbacks", "results", "rejection", "resilience", "students", "placements"],
    relatedAssessment: "PHQ9",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "The result wasn't what you hoped for. You didn't clear the exam, didn't get the college, didn't get selected in placements, or a plan you worked hard on fell apart. Failure and disappointment hurt — sometimes a lot. This article explains why setbacks feel so heavy, how to handle the feelings that come with them, and how to slowly move forward again.",

      whatItIs: [
        "A setback is anything that doesn't go the way you hoped — a low score, a rejection, a lost opportunity, or a goal you couldn't reach. Disappointment is the natural feeling that follows. It's a sign that something mattered to you.",
        "Everyone faces failure at some point. It doesn't mean you are a failure. What helps most is resilience — your ability to cope, adapt and keep going after something difficult. The good news is that resilience isn't something only a few lucky people are born with. Like a muscle, it can be built with practice.",
      ],

      signs: {
        items: [
          "Feeling sad, numb, angry or ashamed after the setback",
          "Replaying what went wrong again and again",
          "Thinking \"I'm useless\" or \"I'll never succeed\"",
          "Avoiding friends, family or social media to escape comparison or questions",
          "Losing motivation to try again or start something new",
          "Trouble sleeping, eating or concentrating",
          "Worrying about what others — parents, relatives, classmates — will think",
        ],
        note: "These feelings are a normal reaction to disappointment, and they usually ease with time and support. Pay attention if they stay strong for weeks or keep getting worse.",
      },

      whyItHappens: {
        paragraphs: [
          "Failure hurts most when we've put a lot of time, effort and hope into something. It can feel even heavier when we tie the result to our identity or worth.",
        ],
        causes: [
          { title: "High investment", text: "months or years of preparation for one exam, interview or goal" },
          { title: "Expectations", text: "from family, teachers, society — or the pressure we put on ourselves" },
          { title: "Comparison", text: "seeing friends clear the exam, get placed or move ahead while you feel stuck" },
          { title: "Fear about the future", text: "worrying that one result has ruined everything ahead" },
          { title: "All-or-nothing thinking", text: "believing that if you didn't succeed completely, you failed completely" },
        ],
        closing: "A setback can change your path, but it doesn't have to cancel your destination. Many people who are successful today faced rejections, drop years or failures along the way.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Let yourself feel it.", text: "It's okay to be upset. Give yourself a little time to feel disappointed before trying to fix anything. Pushing feelings away usually makes them last longer." },
          { title: "Separate the event from yourself.", text: "Try changing \"I am a failure\" to \"This didn't work out this time.\" One result is something that happened — not who you are." },
          { title: "Talk to someone who cares.", text: "A friend, sibling, parent, teacher or mentor. Sharing what you feel makes it lighter, and others may see options you can't see right now." },
          { title: "Look at what you can learn.", text: "Once the first wave of feelings settles, ask gently: What went well? What would I do differently? What was outside my control?" },
          { title: "Remember past comebacks.", text: "Think of a time you got through something hard before. You have done difficult things already, and you can do it again." },
          { title: "Set one small next step.", text: "You don't need the whole plan today. Choose one small, realistic action — like researching other colleges or courses, or making a study plan for the next attempt." },
          { title: "Take care of your body.", text: "Sleep, regular meals and some movement give you the energy to cope and think clearly." },
        ],
        thingsToAvoid: "Avoid harsh self-talk, cutting yourself off from everyone, or numbing the pain with alcohol, smoking or other substances. Try not to make big, permanent decisions while the disappointment is still fresh.",
      },

      whenToSeekHelp: {
        intro: "It's important to reach out for support if:",
        points: [
          "Weeks have passed and you still feel low, empty or hopeless most of the time",
          "You've lost interest in things you used to enjoy",
          "You feel worthless, or that you've let everyone down beyond repair",
          "You're having thoughts of hurting yourself or that life isn't worth living",
        ],
        closing: "No result, rank or rejection defines your worth or your future. If things feel too heavy, please talk to someone you trust or a professional through Find Licensed Professional. You can also take our short mood check to understand how you're feeling.",
      },
    },
    sources: [
      { title: "American Psychological Association — Building your resilience", url: "https://www.apa.org/topics/resilience/building-your-resilience" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-006",
    slug: "sleep-and-mental-wellbeing",
    title: "The Connection Between Sleep and Mental Wellbeing",
    category: "Everyday Self-Care",
    summary: "How sleep and mood affect each other, signs your sleep needs attention, and simple habits for better rest.",
    readingTimeMinutes: 5,
    tags: ["sleep", "insomnia", "rest", "screens", "night shift", "self-care"],
    relatedAssessment: "WHO5",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "Scrolling reels at 2 a.m. Lying awake replaying the day. Waking up already tired. Poor sleep doesn't just make you sleepy — it affects your mood, focus and how well you cope with stress. This article explains how sleep and mental wellbeing are connected, signs your sleep needs attention, and simple habits that can help you rest better.",

      whatItIs: [
        "Sleep is when your body and mind recover. While you sleep, your brain sorts through the day, stores memories, and resets your emotions. That's why things often feel more manageable after a good night's rest.",
        "Sleep and mental health work in both directions. Poor sleep can make you feel more anxious, low or irritable — and stress, anxiety or low mood can make it harder to sleep. Over time, this can become a cycle that's hard to break.",
        "Most adults need around 7 to 9 hours of sleep a night, and teenagers usually need more. But how well you sleep matters as much as how long.",
      ],

      signs: {
        items: [
          "Taking a long time to fall asleep most nights",
          "Waking up often during the night, or very early and not falling back asleep",
          "Waking up tired even after a full night in bed",
          "Feeling sleepy, foggy or unfocused during the day",
          "Getting irritated, anxious or emotional more easily",
          "Needing lots of tea, coffee or energy drinks to get through the day",
          "Lying in bed with your mind racing or worrying",
          "Sleeping much more than usual but still feeling low on energy",
        ],
        note: "Everyone has a bad night now and then. It's worth paying attention when sleep problems happen most nights for a few weeks.",
      },

      whyItHappens: {
        paragraphs: [
          "Many things in modern life make good sleep harder. Often it's a mix of habits, environment and what's on your mind.",
        ],
        causes: [
          { title: "Screens late at night", text: "reels, games, web series and chats keep your brain alert and delay sleep" },
          { title: "Stress and worry", text: "exams, work deadlines, money or relationship problems that keep your mind busy at night" },
          { title: "Irregular routines", text: "sleeping at different times each day, late-night study sessions, or long weekend lie-ins" },
          { title: "Shift work", text: "night shifts or working on another country's time zone, which confuse your body clock" },
          { title: "Your sleep space", text: "noise, heat, light, or sharing a room in a hostel, PG or family home" },
          { title: "Caffeine, nicotine and alcohol", text: "especially later in the day — alcohol may make you drowsy but leads to poorer-quality sleep" },
          { title: "Low mood or anxiety", text: "which can make it hard to fall asleep, stay asleep, or get out of bed" },
        ],
        closing: "The good news is that small, steady changes to your routine can improve sleep over time — though it may take a few weeks to notice the difference.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Keep a regular sleep time.", text: "Try to sleep and wake up at around the same time every day, including weekends. This helps set your body clock." },
          { title: "Put screens away before bed.", text: "Try to stop using your phone or laptop about an hour before sleeping. Keep your phone away from your bed if you can." },
          { title: "Create a calm wind-down routine.", text: "Read, listen to calm music, take a warm bath, or try gentle breathing. Our Relaxation Hub has sleep sounds and breathing exercises that can help." },
          { title: "Make your room sleep-friendly.", text: "Keep it as dark, quiet and cool as you can. An eye mask, earplugs or a fan can help in a shared or noisy space." },
          { title: "Watch what you have in the evening.", text: "Avoid tea, coffee and energy drinks late in the day, and avoid heavy meals, smoking and alcohol close to bedtime." },
          { title: "Get daylight and move during the day.", text: "Spending time outside in daylight and staying active helps you sleep more deeply at night. Try not to exercise hard right before bed." },
          { title: "Don't force it.", text: "If you're still awake after about 20 minutes, get up, sit somewhere comfortable and do something calm until you feel sleepy, then go back to bed." },
          { title: "Write down your worries.", text: "If your mind races at night, jot down what's bothering you and one next step. It can help your brain let go until morning." },
        ],
        thingsToAvoid: "Avoid using alcohol or sleeping pills that weren't prescribed for you to help you sleep. Long daytime naps can also make it harder to sleep at night — if you nap, keep it short and early in the day.",
      },

      whenToSeekHelp: {
        intro: "It's a good idea to talk to a doctor or professional if:",
        points: [
          "You've had trouble sleeping most nights for several weeks",
          "Tiredness is affecting your studies, work, driving or safety",
          "You snore loudly, stop breathing or gasp in your sleep (someone may notice this)",
          "Poor sleep comes with feeling low, anxious or hopeless most of the time",
        ],
        closing: "Sleep problems can be linked to stress, anxiety, low mood or other health issues, and many of these can be treated. You can take our short wellbeing check, or find a qualified professional through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "NHS Every Mind Matters — How to fall asleep faster and sleep better", url: "https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/how-to-fall-asleep-faster-and-sleep-better/" },
      { title: "NHS Lothian — Sleep: A guide for patients", url: "https://policyonline.nhslothian.scot/wp-content/uploads/2025/10/Sleep-A-guide-for-patients.pdf" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-007",
    slug: "healthy-daily-routine",
    title: "Building a Healthy Daily Routine for Mental Wellbeing",
    category: "Everyday Self-Care",
    summary: "Why routine helps your mind, five evidence-based habits to build into your day, and how to get moving even when you're busy.",
    readingTimeMinutes: 6,
    tags: ["routine", "habits", "exercise", "physical activity", "yoga", "self-care", "wellbeing"],
    relatedAssessment: "WHO5",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "Some days just slip away — waking up late, rushing, skipping meals, scrolling until midnight. When life feels chaotic, our mind often does too. A simple daily routine won't solve every problem, but it can give your day structure, steady your mood and make stress easier to handle. This article explains why routine helps, and simple habits — including staying active — you can build into your day.",

      whatItIs: [
        "A healthy routine is simply a set of regular habits that support your body and mind — like sleeping and waking at similar times, eating regular meals, moving your body, and making time for people and things you enjoy.",
        "Routine helps because it reduces the number of decisions you have to make, gives you a sense of control, and makes sure the basics don't get skipped when life gets busy.",
        "It doesn't need to be strict or perfect. A good routine is flexible, realistic and built around your life — whether you're a student, working, or managing a home.",
      ],

      signs: {
        items: [
          "Your sleep and wake-up times change a lot from day to day",
          "You often skip meals or eat at very irregular times",
          "You spend most of the day sitting with very little movement",
          "Hours disappear into scrolling without you noticing",
          "You rarely have time for friends, family or hobbies",
          "You feel rushed, scattered or behind most of the time",
          "Weekends feel just as tiring as weekdays",
        ],
        note: "If several of these sound familiar, your days may be working against your wellbeing. Small changes can make a real difference.",
      },

      whyItHappens: {
        paragraphs: [
          "Routines often fall apart without us noticing. Long study or work hours, travel, family responsibilities and screens can slowly push out the habits that keep us well.",
        ],
        causes: [
          { title: "Busy schedules", text: "college, coaching, office hours and long commutes that leave little time for anything else" },
          { title: "Irregular shifts", text: "night shifts or changing work timings that disrupt sleep and meals" },
          { title: "Screens", text: "phones and laptops that quietly fill up free time and push bedtime later" },
          { title: "Life changes", text: "moving to a new city, starting a new job or college, or holidays that break old habits" },
          { title: "Low energy or low mood", text: "which makes it harder to start habits — and missing those habits then makes mood lower" },
        ],
        closing: "The good news is that you don't need to change everything at once. Adding one or two small habits at a time is often what works best.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Anchor your day with sleep.", text: "Try to wake up and go to bed at around the same time every day. A steady sleep pattern is the base of a healthy routine." },
          { title: "Eat regular meals.", text: "Try not to skip breakfast or eat very late at night. Regular meals and enough water keep your energy and mood more stable." },
          { title: "Move your body every day.", text: "Physical activity is one of the best things you can do for your mental health. The World Health Organization recommends adults aim for at least 150 minutes of moderate activity a week — that's just over 20 minutes a day. A brisk walk, yoga, cycling, dancing, a sport, or even climbing stairs all count. Children and teenagers need about an hour a day." },
          { title: "Connect with someone.", text: "Make time each day for a real conversation — a call home, chai with a friend, or dinner together with family without phones." },
          { title: "Learn something new.", text: "Cooking a new dish, a few pages of a book, a language, or an instrument. Learning builds confidence and gives you a sense of progress." },
          { title: "Give a little.", text: "Small acts of kindness — helping a friend, thanking someone, volunteering — can lift your own mood as well as theirs." },
          { title: "Pause and notice the present.", text: "Take a few minutes each day to slow down: notice your breathing, your surroundings, or how you feel. Our Relaxation Hub has short breathing and meditation exercises to help." },
          { title: "Start small and be kind to yourself.", text: "Pick one or two habits to start with. If you miss a day, just pick it up again the next day — consistency matters more than perfection." },
        ],
        thingsToAvoid: "Avoid trying to overhaul your whole life in one week, or setting rules so strict they become another source of stress. If you have a health condition or haven't exercised in a long time, start gently and check with a doctor before doing intense exercise.",
      },

      whenToSeekHelp: {
        intro: "A routine can support your wellbeing, but it's not a fix for everything. It's a good idea to get support if:",
        points: [
          "You feel too tired, low or unmotivated to manage basic daily tasks for weeks",
          "You've lost interest in things you used to enjoy",
          "Changes in your sleep, appetite or energy aren't improving",
          "You feel hopeless, or that you can't cope",
        ],
        closing: "If simple habits feel impossible right now, that's not a personal failure — it can be a sign that you need extra support. You can take our short wellbeing check, or find a qualified professional through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "NHS — 5 steps to mental wellbeing", url: "https://www.nhs.uk/mental-health/self-help/guides-tools-and-activities/five-steps-to-mental-wellbeing/" },
      { title: "World Health Organization — Physical activity fact sheet", url: "https://www.who.int/europe/news-room/fact-sheets/item/physical-activity" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-008",
    slug: "digital-wellbeing-screen-time",
    title: "Digital Wellbeing: Managing Screen Time and Social Media Stress",
    category: "Everyday Self-Care",
    summary: "How screens and social media affect your mood, signs your phone is taking over, and practical ways to take back control.",
    readingTimeMinutes: 5,
    tags: ["screen time", "social media", "phone", "reels", "gaming", "comparison", "digital wellbeing"],
    relatedAssessment: "WHO5",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "You open Instagram for a minute and look up an hour later. You see friends on holidays, getting placed, getting engaged — and feel like you're falling behind. Your phone is how you stay connected, study, work and relax, so giving it up isn't realistic. But how you use it matters. This article explains how screens and social media affect your mental wellbeing, signs your phone is taking over, and practical ways to take back control.",

      whatItIs: [
        "Digital wellbeing means having a healthy relationship with your phone, apps and the internet — using them in ways that help you, rather than leaving you drained, anxious or distracted.",
        "Social media isn't all bad. It can help you stay in touch with friends and family, find support, learn new things, and feel part of a community. The problem starts when it begins to control you, instead of you controlling it.",
        "The World Health Organization describes \"problematic\" social media use as having addiction-like signs: finding it hard to control how much you use it, giving up other activities for it, and seeing it cause problems in your daily life. This kind of use has been linked to lower wellbeing, poorer studies and worse sleep.",
      ],

      signs: {
        items: [
          "Reaching for your phone without thinking, even when you don't need it",
          "Losing track of time while scrolling, watching reels or gaming",
          "Feeling anxious, restless or irritated when you can't check your phone",
          "Using your phone late at night and sleeping later because of it",
          "Feeling worse about yourself after scrolling — less attractive, less successful, left out",
          "Checking likes, comments or messages again and again",
          "Studies, work or time with people suffering because of screen time",
          "Trying to cut down but finding you can't",
        ],
        note: "Most people recognise a few of these. It becomes a concern when screen use regularly affects your mood, sleep, studies, work or relationships.",
      },

      whyItHappens: {
        paragraphs: [
          "Many apps are designed to keep you using them for as long as possible. Endless feeds, notifications, autoplay and likes keep your brain looking for the next small reward — which makes it very easy to keep scrolling.",
        ],
        causes: [
          { title: "Comparison", text: "seeing only the highlight reels of others' lives — results, jobs, weddings, trips — and measuring your life against them" },
          { title: "Fear of missing out", text: "worrying you'll miss news, plans or conversations if you're not online" },
          { title: "Escaping difficult feelings", text: "using the phone to avoid boredom, stress, loneliness or sadness" },
          { title: "Constant notifications", text: "WhatsApp groups, emails and alerts that keep pulling your attention back" },
          { title: "Negative content", text: "upsetting news, arguments, trolling or cyberbullying" },
          { title: "Habit", text: "picking up the phone automatically — first thing in the morning, during meals, before sleep" },
        ],
        closing: "Understanding why you reach for your phone is the first step to changing the habit — without guilt.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Check your screen time.", text: "Use your phone's built-in Digital Wellbeing or Screen Time settings to see how much time you spend and on which apps. Just noticing can be eye-opening." },
          { title: "Turn off non-essential notifications.", text: "Keep only the ones that truly matter, like calls and important messages. Mute busy groups." },
          { title: "Set app time limits.", text: "Use the app timers on your phone for social media, games or video apps that tend to take over your time." },
          { title: "Create phone-free times.", text: "Try no phone for the first 30 minutes after waking, during meals, and for an hour before bed. Charge it outside your bedroom if you can." },
          { title: "Clean up your feed.", text: "Mute or unfollow accounts that make you feel bad about yourself. Follow ones that inspire, teach or make you laugh." },
          { title: "Swap scrolling for something else.", text: "When you catch yourself scrolling out of habit, try a short walk, a stretch, a call to a friend, or a breathing exercise from our Relaxation Hub." },
          { title: "Remember: it's a highlight reel.", text: "People mostly post their best moments. You're comparing your everyday life to someone else's best edit." },
        ],
        thingsToAvoid: "Avoid using your phone as the only way to cope with stress, loneliness or sadness, and avoid scrolling in bed late at night. Don't engage with trolls or online arguments that leave you upset — it's okay to block, report and log off.",
      },

      whenToSeekHelp: {
        intro: "It's a good idea to talk to someone if:",
        points: [
          "You've tried to cut down but can't, and it's affecting your studies, work, sleep or relationships",
          "Social media regularly leaves you feeling anxious, low or bad about yourself",
          "You're being bullied, harassed or threatened online",
          "You feel hopeless or have thoughts of hurting yourself",
        ],
        closing: "If you're being harassed online, save evidence and tell someone you trust. If screen time has become hard to control or is affecting your mood, a professional can help — you can take our short wellbeing check, or find support through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "World Health Organization (Europe) — Teens, screens and mental health", url: "https://www.who.int/europe/news/item/25-09-2024-teens--screens-and-mental-health" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-009",
    slug: "panic-attacks",
    title: "Panic Attacks: What They Are and What to Do During One",
    category: "Everyday Self-Care",
    summary: "What a panic attack feels like, why it happens, step-by-step what to do during one, and when to get medical help.",
    readingTimeMinutes: 5,
    tags: ["panic attack", "anxiety", "breathing", "racing heart", "grounding", "fear"],
    relatedAssessment: "GAD7",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "Your heart suddenly races. Your chest feels tight, your hands shake, and you feel like something terrible is about to happen. A panic attack can be one of the most frightening experiences — but it is not dangerous, and it will pass. This article explains what a panic attack is, why it happens, and exactly what you can do when one starts.",

      whatItIs: [
        "A panic attack is a sudden rush of intense fear or anxiety, along with strong physical feelings in your body. It can happen in a stressful situation — like an exam hall, crowded train or interview — or sometimes out of nowhere, even while resting or asleep.",
        "Most panic attacks last between 5 and 20 minutes. The feelings usually peak within about 10 minutes and then slowly fade. Afterwards, you may feel tired or drained.",
        "Panic attacks feel scary, but they are not harmful. Your body is reacting as if it's in danger, even though it isn't. Many people have one or two panic attacks in their life. If they keep happening, or you live in fear of the next one, it's worth getting support — this is very treatable.",
      ],

      signs: {
        items: [
          "A pounding, racing or fluttering heartbeat",
          "Feeling short of breath, or like you can't get enough air",
          "Tightness or discomfort in the chest",
          "Sweating, shaking or trembling",
          "Feeling dizzy, light-headed or faint",
          "Feeling sick, or a churning stomach",
          "Tingling or numbness in your hands, feet or lips",
          "Feeling hot or cold suddenly",
          "Feeling unreal, or detached from yourself or your surroundings",
          "A strong fear that you're losing control, going crazy, or dying",
        ],
        note: "You won't necessarily feel all of these. Because some signs — like chest pain and a racing heart — can feel similar to a heart problem, it's important to get checked by a doctor, especially the first time.",
      },

      whyItHappens: {
        paragraphs: [
          "Panic attacks happen when your body's natural alarm system — the \"fight or flight\" response — switches on when there's no real danger. Your body releases adrenaline, your heart speeds up and your breathing becomes fast and shallow. Fast breathing then causes more dizziness and tingling, which can make you even more frightened.",
        ],
        causes: [
          { title: "Ongoing stress", text: "exams, work pressure, money worries or family problems building up over time" },
          { title: "Anxiety", text: "people who often feel anxious are more likely to have panic attacks" },
          { title: "Triggering situations", text: "crowds, closed spaces, travel, public speaking or a past frightening experience" },
          { title: "Caffeine, nicotine and alcohol", text: "lots of tea, coffee, energy drinks, smoking or drinking can make symptoms worse" },
          { title: "Lack of sleep", text: "tiredness makes your body more sensitive to stress" },
          { title: "Family history", text: "panic attacks can sometimes run in families" },
        ],
        closing: "Sometimes there's no clear reason at all. That doesn't mean something is wrong with you — panic attacks are common and can be managed.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Recognise it's a panic attack.", text: "Tell yourself: \"This is a panic attack. It feels awful, but it can't hurt me, and it will pass.\" Naming it takes away some of its power." },
          { title: "Stay where you are if it's safe.", text: "You don't need to run. If you're driving, pull over safely. Sit down if you can." },
          { title: "Slow down your breathing.", text: "Breathe in gently through your nose for 4 counts, then breathe out slowly through your mouth for 6 counts. Keep going for a few minutes. A longer out-breath helps calm your body. Our Relaxation Hub has guided breathing exercises you can practise." },
          { title: "Ground yourself with your senses.", text: "Look around and name 5 things you can see, 4 things you can touch, 3 things you can hear, 2 things you can smell and 1 thing you can taste. This brings your mind back to the present." },
          { title: "Don't fight the feelings.", text: "Trying to force them away can make them stronger. Let the feelings rise and fall, like a wave, while you keep breathing slowly." },
          { title: "Afterwards, be gentle with yourself.", text: "Drink some water, rest, and do something calming. Tell someone you trust what happened." },
          { title: "Help prevent future attacks.", text: "Practise slow breathing daily (not just during attacks), stay active, get enough sleep, and cut down on caffeine, energy drinks, alcohol and smoking." },
        ],
        thingsToAvoid: "Avoid breathing into a paper bag, and avoid caffeine, energy drinks, alcohol or smoking to calm down — these can make symptoms worse. Try not to start avoiding places or situations out of fear of another attack; avoidance can make panic stronger over time.",
      },

      whenToSeekHelp: {
        intro: "Get emergency medical help right away (call 112) if:",
        points: [
          "It's your first time having these symptoms and you're not sure what's happening",
          "You have chest pain, pain spreading to your arm, jaw or back, or an irregular heartbeat",
          "The symptoms last longer than 20 minutes or keep getting worse",
          "You faint, or have severe difficulty breathing",
        ],
        closing: "It's always better to get checked than to guess — a doctor can rule out a heart or other medical problem. If you have panic attacks often, or worry a lot about the next one, talk to a doctor or mental health professional. Talking therapies like CBT work very well for panic. You can take our short anxiety check, or find support through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "Mental Health Foundation — Panic attacks", url: "https://www.mentalhealth.org.uk/a-to-z/p/panic-attacks" },
      { title: "HSE (Ireland's national health service) — Panic attacks", url: "https://www2.hse.ie/conditions/panic-attacks/" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-010",
    slug: "when-to-talk-to-a-professional",
    title: "When Should You Consider Talking to a Mental Health Professional?",
    category: "Support & Professional Help",
    summary: "Signs it may be time to get professional support, common myths that hold people back, and how to take the first step.",
    readingTimeMinutes: 6,
    tags: ["therapy", "counselling", "getting help", "stigma", "support", "Tele-MANAS", "professional"],
    // Brief specified "K10", but the frontend has no K10 assessment yet (only a
    // backend scoring endpoint). WHO-5 is the "wellbeing check" the closing
    // text refers to; switch to K10 once a K10 page exists.
    relatedAssessment: "WHO5",
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "\"It's just a phase.\" \"Others have it worse.\" \"What will people say?\" Many of us wait a long time before asking for help with our mental health — sometimes until things feel unbearable. But you don't need to be in a crisis to talk to a professional. This article explains when it may be time to get support, why people often hold back, and how to take the first step.",

      whatItIs: [
        "Talking to a mental health professional means getting support from someone trained to help with emotional and mental health difficulties — like a counselor, psychologist or psychiatrist. It's a safe, confidential space to talk about what you're going through, understand it better, and learn ways to cope.",
        "Seeking help is not a sign of weakness, and it doesn't mean you're \"mad\" or broken. Just like you'd see a doctor for a fever that won't go away, it makes sense to get support when your mind is struggling. The earlier you reach out, the easier things usually are to manage.",
        "Help is more available than many people realise. In India, Tele-MANAS is a free, government-run mental health helpline available 24/7, where trained counsellors can talk with you in your preferred language and guide you to further care if needed. You'll find its number in the help box on this page.",
      ],

      signs: {
        items: [
          "Feeling low, anxious, stressed or empty most days for more than two weeks",
          "Your feelings are affecting your studies, work, relationships or daily tasks",
          "Changes in sleep, appetite or energy that aren't getting better",
          "Losing interest in things you used to enjoy",
          "Feeling overwhelmed, like you can't cope, no matter what you try",
          "Using alcohol, smoking, substances, or your phone to escape how you feel",
          "Going through something difficult — a loss, breakup, trauma or big change — that you can't move past",
          "Friends or family telling you they're worried about you",
          "Thoughts of hurting yourself, or feeling that life isn't worth living",
        ],
        note: "You don't need to tick every box. If something has been bothering you for a while and self-help isn't enough, that's reason enough to reach out.",
      },

      whyItHappens: {
        paragraphs: [
          "If getting help is useful, why do so many people delay it? Often it's because of worries or beliefs that make reaching out feel harder than it really is.",
        ],
        causes: [
          { title: "\"It's not serious enough\"", text: "many people think therapy is only for severe problems. In fact, it can help with everyday stress, relationships and life decisions too" },
          { title: "Stigma", text: "fear of being judged or labelled, or of what family, relatives or society will think" },
          { title: "\"I should handle it myself\"", text: "believing that asking for help means you've failed or are weak" },
          { title: "Not knowing where to start", text: "confusion about who to see, how much it costs, or what happens in a session" },
          { title: "Worry about privacy", text: "fear that others — parents, college or employers — will find out" },
          { title: "Cost or access", text: "thinking help is too expensive or not available nearby" },
        ],
        closing: "These worries are understandable, but they shouldn't stop you. Conversations with professionals are generally confidential, free options exist, and taking care of your mind is just as important as taking care of your body.",
      },

      whatYouCanDo: {
        steps: [
          { title: "Start by telling someone you trust.", text: "A friend, sibling, parent, teacher or partner. You might say: \"I've been feeling really low lately and I don't know why. Can we talk?\" You don't need the perfect words." },
          { title: "Be clear about what you need.", text: "Sometimes you want advice, sometimes you just want someone to listen. It's okay to say: \"I don't need solutions right now — I just need you to hear me.\"" },
          { title: "Use free, confidential options.", text: "You can call Tele-MANAS for free counselling, any time of day. Many colleges and workplaces also offer free counsellors — check with your student welfare office or HR." },
          { title: "Find the right kind of professional.", text: "A counselor or psychologist can help through talking therapy. A psychiatrist is a medical doctor who can also prescribe medicine if needed. You can explore professionals through Find Licensed Professional." },
          { title: "Write down what you want to share.", text: "Before your first conversation, note how you've been feeling, for how long, and what's affecting you. It makes it easier to explain." },
          { title: "Give it a little time.", text: "The first session can feel awkward. It's okay if it takes a few conversations to feel comfortable — or to try a different professional if it doesn't feel like a good fit." },
        ],
        thingsToAvoid: "Avoid waiting until things become unbearable, or relying only on internet searches to diagnose yourself. And avoid stopping or starting any medicine on your own — always talk to a doctor first.",
      },

      whenToSeekHelp: {
        intro: "Please reach out for urgent help right away if:",
        points: [
          "You're having thoughts of hurting yourself or ending your life",
          "You feel unable to keep yourself safe",
          "You're seeing or hearing things others don't, or feel very confused",
          "Someone you know is in immediate danger",
        ],
        closing: "In these situations, call one of the free 24/7 helplines in the box below, or call 112 for emergencies. For everything else, it's never too early to talk to someone. Not sure where you stand? Take our short wellbeing check, or explore qualified professionals through Find Licensed Professional.",
      },
    },
    sources: [
      { title: "Ministry of Health & Family Welfare, Government of India — Tele-MANAS", url: "https://telemanas.mohfw.gov.in/home" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
  {
    id: "article-011",
    slug: "crisis-warning-signs-and-help",
    title: "Warning Signs of a Crisis and Where to Get Help in India",
    category: "Support & Professional Help",
    summary: "How to recognise when you or someone you care about may be in crisis, what to do, and free 24/7 help available in India.",
    readingTimeMinutes: 6,
    tags: ["crisis", "suicide prevention", "helpline", "KIRAN", "Tele-MANAS", "emergency", "help someone"],
    relatedAssessment: null,
    showCrisisBoxAtTop: true,
    language: "en",
    reviewStatus: "pending",
    sections: {
      intro: "If you are thinking about ending your life or are in danger right now, please stop reading and reach out now — call one of the free 24/7 helplines shown above, or call 112 for emergencies. You don't have to go through this alone. This article explains the warning signs that someone may be in crisis, what you can do if it's you or someone you care about, and where to find free help in India.",

      whatItIs: [
        "A mental health crisis is when someone's distress becomes so intense that they feel they can't cope, or they may be at risk of harming themselves. It can build up slowly over time, or come suddenly after a painful event.",
        "Thoughts of suicide are more common than many people think, and having them doesn't make someone weak or bad. They are usually a sign of overwhelming pain — not a real wish to die, but a wish for the pain to stop.",
        "Crises can pass, and help works. Many people who once felt there was no way out have gone on to feel better with the right support. Reaching out — to a helpline, a professional, or someone you trust — can make a real difference.",
      ],

      signs: {
        items: [
          "Talking about wanting to die, or about ending their life",
          "Saying things like \"No one will miss me,\" \"I'm a burden,\" or \"There's no point anymore\"",
          "Talking about feeling hopeless, trapped, or in unbearable pain",
          "Looking for ways to end their life",
          "Saying goodbye to people, giving away valued possessions, or putting their affairs in order",
          "Withdrawing from family, friends and activities they used to enjoy",
          "Using more alcohol or other substances",
          "Extreme mood swings, or acting recklessly",
          "A sudden calm or cheerfulness after a long period of feeling very low",
        ],
        note: "Even one of these signs is worth taking seriously, especially if it's new or getting worse. Trust your instincts — it's always better to ask and check than to wait.",
      },

      whyItHappens: {
        paragraphs: [
          "There is rarely one single reason someone reaches a crisis point. Usually it's a mix of pain building up over time, sometimes followed by an event that feels like the last straw.",
        ],
        causes: [
          { title: "Painful life events", text: "the loss of a loved one, a breakup, exam results, job loss, or failure that feels impossible to recover from" },
          { title: "Ongoing pressures", text: "debt, family conflict, academic or work pressure, or feeling trapped in a situation" },
          { title: "Mental health difficulties", text: "depression, severe anxiety, or substance use that isn't getting support" },
          { title: "Isolation", text: "feeling alone, unsupported, or that no one would understand" },
          { title: "Harm or trauma", text: "bullying, harassment, abuse or violence, now or in the past" },
          { title: "Past experiences", text: "a previous suicide attempt, or losing someone close to suicide" },
        ],
        closing: "Having these experiences doesn't mean someone will reach a crisis — but it does mean they may need extra care and support. With help, people can and do get through the hardest times.",
      },

      whatYouCanDo: {
        steps: [
          { title: "If it's you: reach out now.", text: "Call a free 24/7 helpline from the help box on this page. Trained counsellors are there to listen, without judging you. You don't need to know what to say — \"I'm not okay\" is enough to start." },
          { title: "If it's you: tell someone close.", text: "Let a friend, family member, teacher or anyone you trust know how you're feeling. Ask them to stay with you if you feel unsafe." },
          { title: "If it's you: make your space safer.", text: "If you can, move away from or give someone else anything you might use to hurt yourself. Go to a place where other people are around." },
          { title: "If it's someone else: ask directly.", text: "Ask gently but clearly: \"Are you thinking about ending your life?\" Asking does not put the idea in their head — it often brings relief and shows you care." },
          { title: "If it's someone else: listen without judging.", text: "Let them talk. Don't argue, lecture or tell them how they should feel. Simply say: \"I'm glad you told me. I'm here for you.\"" },
          { title: "If it's someone else: help them stay safe.", text: "If they're at risk, don't leave them alone. Help remove anything they could use to harm themselves, and stay with them while you get help." },
          { title: "If it's someone else: connect them to help.", text: "Help them call a helpline, contact a professional, or go to the nearest hospital emergency department. Offer to make the call with them." },
          { title: "Follow up.", text: "Check in again over the next days and weeks. Continued care and connection really do help." },
        ],
        thingsToAvoid: "If someone tells you they're thinking about suicide, never promise to keep it a secret — their safety comes first. Don't ignore the signs, don't treat it as \"attention-seeking,\" and don't try to handle a serious crisis entirely on your own. Look after yourself too, and get support for yourself if you need it.",
      },

      whenToSeekHelp: {
        intro: "Get emergency help immediately — call 112 or go to the nearest hospital emergency department — if:",
        points: [
          "Someone has harmed themselves or is about to",
          "Someone has a plan to end their life, or has the means to do it",
          "Someone cannot keep themselves safe right now",
          "You feel unable to keep yourself safe",
        ],
        closing: "For support at any time of day or night, the free 24/7 helplines in the box on this page can talk with you in many Indian languages. When the immediate crisis has passed, ongoing support from a counselor, psychologist or psychiatrist can help — you can find one through Find Licensed Professional. You matter, and help is always available.",
      },
    },
    sources: [
      { title: "World Health Organization — Suicide: What you should know if you are worried about someone (Depression: Let's Talk)", url: "https://ssc.manipal.edu/docs/WHO%204%20-suicide.pdf" },
      { title: "Ministry of Health & Family Welfare, Government of India — Tele-MANAS", url: "https://telemanas.mohfw.gov.in/home" },
      { title: "National Institute of Mental Health (US) — Warning Signs of Suicide", url: "https://www.nimh.nih.gov/health/publications/warning-signs-of-suicide" },
    ],
    lastReviewedDate: null,
    reviewedBy: null,
  },
];

export function getArticleBySlug(slug: string): Article | undefined {
  return articles.find((article) => article.slug === slug);
}
