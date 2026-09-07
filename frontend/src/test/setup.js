import '@testing-library/jest-dom';
import { configure } from '@testing-library/dom';

// findBy*/waitFor default to one second, which is fine for a small component and not fine for a
// large one rendering under a loaded suite. The work is real; only the patience was wrong.
// 10s, not 5: the CI coverage run instruments every module, and the dashboards this suite
// renders take long enough under that to outrun a shorter wait. Nothing here is waiting on a
// network -- only on React finishing work that is genuinely slower when measured.
configure({ asyncUtilTimeout: 10000 });
