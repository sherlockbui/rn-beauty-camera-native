import {File} from 'expo-file-system';

/** Deletes a file returned by BeautyCamera when the app no longer needs it. */
export const deleteBeautyCameraFile = (uri?: string | null) => {
  if (!uri?.startsWith('file://')) return;

  try {
    const file = new File(uri);
    if (file.exists) file.delete();
  } catch (error) {
    console.warn('Could not delete temporary beauty camera file:', error);
  }
};
