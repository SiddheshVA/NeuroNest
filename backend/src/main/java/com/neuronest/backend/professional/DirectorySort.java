package com.neuronest.backend.professional;

// The directory's sort options (GET /api/professionals?sort=...). No sort keeps the default order: real
// listings first, then by name. See PublicProfessionalRepository.ORDER for what each one sorts by.
public enum DirectorySort { TOP_RATED, MOST_REVIEWED, LOWEST_FEE, MOST_EXPERIENCED }
