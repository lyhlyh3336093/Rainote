import { useRouter } from 'vue-router';
const useJump = () => {
  const type = {
    2: "docx",
    3: "sheets",
    4: "base",
  };
  const router = useRouter();
  const click = (data) => {
    const { href } = router.resolve({
      name: type[data.noteType],
      params: {
        id: data.id,
      },
    });
    window.open(href, "_blank");
  };
  return {
    click,
  }
}
export default useJump;