// Licence status (NeuroNest is a non-commercial college project):
// - PHQ-9 / GAD-7: Pfizer, no permission required.
// - WHO-5: © WHO, CC BY-NC-SA 3.0 IGO. Non-commercial, credit WHO, adaptations must be marked and shared alike, so use the official wording.
// - K10: free to use; keep the copyright line and the citation.
// - SCI: free for non-commercial use (CC BY-NC 4.0). Commercial use is NOT covered; revisit if NeuroNest is ever commercialised.
// - PSS-10: TODO request permission through Mapi Research Trust ePROVIDE (the request itself is free) before a public launch.

import type { AssessmentType } from "./types";

export interface AssessmentSource {
  credit: string;
  citation: string;
}

const PFIZER_CREDIT =
  "Developed by Drs. Robert L. Spitzer, Janet B.W. Williams, Kurt Kroenke and colleagues, with an educational grant from Pfizer Inc.";

export const ASSESSMENT_SOURCES: Record<AssessmentType, AssessmentSource> = {
  who5: {
    credit:
      "© World Health Organization 2024. Licence: CC BY-NC-SA 3.0 IGO. Developed by Per Bech and colleagues at the Psychiatric Centre North Zealand, Denmark.",
    citation: "World Health Organization. The World Health Organization-Five Well-Being Index (WHO-5). Geneva: WHO; 2024.",
  },
  phq9: {
    credit: PFIZER_CREDIT,
    citation:
      "Kroenke K, Spitzer RL, Williams JBW. The PHQ-9: validity of a brief depression severity measure. J Gen Intern Med. 2001;16(9):606-613.",
  },
  gad7: {
    credit: PFIZER_CREDIT,
    citation:
      "Spitzer RL, Kroenke K, Williams JBW, Löwe B. A brief measure for assessing generalized anxiety disorder: the GAD-7. Arch Intern Med. 2006;166(10):1092-1097.",
  },
  pss10: {
    credit: "Perceived Stress Scale © 1994 Sheldon Cohen. All rights reserved.",
    citation:
      "Cohen S, Kamarck T, Mermelstein R. A global measure of perceived stress. J Health Soc Behav. 1983;24(4):385-396. 10-item version: Cohen S, Williamson G. Perceived stress in a probability sample of the United States. In: Spacapan S, Oskamp S, eds. The Social Psychology of Health. Sage; 1988:31-67.",
  },
  k10: {
    credit: "© Ronald C. Kessler, PhD. All rights reserved.",
    citation:
      "Kessler RC, Barker PR, Colpe LJ, Epstein JF, Gfroerer JC, Hiripi E, Howes MJ, Normand SLT, Manderscheid RW, Walters EE, Zaslavsky AM. Screening for serious mental illness in the general population. Arch Gen Psychiatry. 2003;60(2):184-189.",
  },
  sci: {
    credit:
      "Sleep Condition Indicator © Sleepio Limited. Licensed free for non-commercial use under CC BY-NC 4.0. Developed by Colin A. Espie and colleagues.",
    citation:
      "Espie CA, Kyle SD, Hames P, Gardani M, Fleming L, Cape J. The Sleep Condition Indicator: a clinical screening tool to evaluate insomnia disorder. BMJ Open. 2014;4(3):e004183.",
  },
};
